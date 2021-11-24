package net.ttddyy.evernote.rest;

import com.evernote.clients.NoteStoreClient;
import com.evernote.edam.error.EDAMNotFoundException;
import com.evernote.edam.error.EDAMSystemException;
import com.evernote.edam.error.EDAMUserException;
import com.evernote.edam.notestore.NoteFilter;
import com.evernote.edam.notestore.NoteList;
import com.evernote.edam.type.Note;
import com.evernote.edam.type.NoteSortOrder;
import com.evernote.thrift.TException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.metrics.CounterService;
import org.springframework.boot.actuate.metrics.GaugeService;
import org.springframework.boot.autoconfigure.web.ErrorAttributes;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.http.HttpStatus;
import org.springframework.social.evernote.api.Evernote;
import org.springframework.social.evernote.api.EvernoteException;
import org.springframework.social.evernote.api.StoreClientHolder;
import org.springframework.social.evernote.api.StoreOperations;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StopWatch;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.view.InternalResourceView;
import org.springframework.web.util.WebUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Iterator;

/**
 * @author Tadaya Tsuyukubo
 */
@RestController
@RequestMapping("/{storeName:noteStore|userStore}")
public class StoreOperationController {

	@Autowired
	private Evernote evernote;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ParameterNameDiscoverer parameterNameDiscoverer;

	@Autowired
	private ParameterJavaTypeDiscoverer parameterJavaTypeDiscoverer;

	@Autowired
	private CounterService counterService;

	@Autowired
	private GaugeService gaugeService;

	@Autowired
	private ErrorAttributes errorAttributes;


	@RequestMapping(value = "/{methodName}", method = RequestMethod.POST)
	public Object invoke(@PathVariable String storeName, @PathVariable String methodName,
						 @RequestBody(required = false) JsonNode jsonNode,
						 HttpServletRequest request, HttpServletResponse response) throws Exception {

		final StoreOperations storeOperations = getStoreOperations(storeName);
		searchNotes();
		
		final Class<?> storeOperationsClass = storeOperations.getClass();
		final Class<?> actualStoreClientClass = resolveStoreClientClass(storeOperations);  // underlying ~StoreClient class.

		// In ~StoreClient class, method names are currently unique. passing null to paramTypes arg means find method by name.
		final Method method = ReflectionUtils.findMethod(storeOperationsClass, methodName, null);
		final Method actualMethod = ReflectionUtils.findMethod(actualStoreClientClass, methodName, null);
		if (method == null || actualMethod == null) {
			final String message = String.format("Cannot find methodName=[%s] on [%s].", methodName, actualStoreClientClass);
			throw new EvernoteRestException(message);
		}

		Object[] params = null;
		if (jsonNode != null) {
			// Cannot retrieve parameter names and generic method parameter type from interface, even though classes
			// are compiled with debugging information.
			// ~StoreClient class, which is an underlying implementation class of ~StoreOperations, uses same parameter
			// names and types. Thus, for now, use underlying actual ~StoreClient class to resolve names and types.
			// Java8 with StandardReflectionParameterNameDiscoverer class, it may be possible to retrieve param names from
			// interface. (haven't checked)
			params = resolveParameters(actualMethod, jsonNode);
		}

		// metric format:
		//   evernote.api.[userStore|noteStore].<method>.[succeeded|failed]
		//   evernote.api.[userStore|noteStore].<method>.response
		final String metricNamePrefix = "evernote.api." + storeName + "." + methodName; // evernote.api.[userStore|noteStore].<method>

		final StopWatch stopWatch = new StopWatch();
		try {

			stopWatch.start();
			Object result = ReflectionUtils.invokeMethod(method, storeOperations, params);
			stopWatch.stop();

			counterService.increment(metricNamePrefix + ".succeeded");
			gaugeService.submit(metricNamePrefix + ".response", stopWatch.getTotalTimeMillis());

			return result;
		} catch (Exception e) {

			if (stopWatch.isRunning()) {
				stopWatch.stop();
			}

			counterService.increment(metricNamePrefix + ".failed");

			final String message = String.format(
					"Failed to invoke method. method=[%s], storeClient=[%s], params=[%s], caused-by=[%s] exception-message=[%s]",
					method.getName(), actualStoreClientClass, ObjectUtils.nullSafeToString(params), e.getClass().getName(), e.getMessage()
			);

			if (e instanceof EvernoteException && ((EvernoteException) e).isEDAMException()) {
				// For EDAM*Exception, return status=BAD_REQUEST(400), and do not throw exception, so that server-side
				// will not write out the exception since this is an client error.
				// Using spring-boot's BasicErrorController to generate response to client

				// expose exception where BasicErrorController can pick-up. maybe too detail...
				((HandlerExceptionResolver) errorAttributes).resolveException(request, response, null, e);  // delegate to spring-boot infrastructure...
				request.setAttribute(WebUtils.ERROR_STATUS_CODE_ATTRIBUTE, HttpStatus.BAD_REQUEST.value());  // response status code

				return new InternalResourceView("/error");
			} else {
				throw new EvernoteRestException(message, e);
			}
		}
	}

	private void searchNotes() throws Exception {
		NoteStoreClient noteStoreClient = evernote.noteStoreClient();
		// Searches are formatted according to the Evernote search grammar.
		// Learn more at
		// http://dev.evernote.com/documentation/cloud/chapters/Searching_notes.php

		// In this example, we search for notes that have the term "EDAMDemo" in
		// the title.
		// This should return the sample note that we created in this demo app.
		String query = "intitle:2020";
//		String query = "";

		// To search for notes with a specific tag, we could do something like
		// this:
		// String query = "tag:tagname";

		// To search for all notes with the word "elephant" anywhere in them:
		// String query = "elephant";

		NoteFilter filter = new NoteFilter();
		filter.setWords(query);
		filter.setOrder(NoteSortOrder.UPDATED.getValue());
		filter.setAscending(false);

		// Find the first 50 notes matching the search
		System.out.println("Searching for notes matching query: " + query);
		NoteList notes = noteStoreClient.findNotes(filter, 0, 50);
		System.out.println("Found " + notes.getTotalNotes() + " matching notes");

		Iterator<Note> iter = notes.getNotesIterator();
		while (iter.hasNext()) {
			Note note = iter.next();
			System.out.println("Note: " + note.getTitle());

			// Note objects returned by findNotes() only contain note attributes
			// such as title, GUID, creation date, update date, etc. The note
			// content
			// and binary resource data are omitted, although resource metadata
			// is included.
			// To get the note content and/or binary resources, call getNote()
			// using the note's GUID.
			Note fullNote = noteStoreClient.getNote(note.getGuid(), true, true, false,
					false);
			System.out.println("Note contains " + fullNote.getResourcesSize()
					+ " resources");
			System.out.println("Note contents: " + fullNote.getContent());
		}
	}

	private StoreOperations getStoreOperations(String storeName) {
		if ("noteStore".equals(storeName)) {
			return evernote.noteStoreOperations();
		} else {
			return evernote.userStoreOperations();
		}
	}

	/**
	 * Based on received json, deserialize parameters.
	 */
	private Object[] resolveParameters(Method actualMethod, JsonNode jsonNode) {
		final String[] parameterNames = parameterNameDiscoverer.getParameterNames(actualMethod);
		if (parameterNames == null) {
			final String message = String.format("Cannot find parameter names for method=[%s].", actualMethod.getName());
			throw new EvernoteRestException(message);
		}

		// to allow jackson to map generic type in collection appropriately, such as List<Long> or List<Short>,
		// object mapper requires JavaType to be provided. Otherwise, generics for number gets default to List<Integer>.
		final JavaType[] parameterJavaTypes = parameterJavaTypeDiscoverer.getParameterJavaTypes(actualMethod);
		return resolveParameterValues(parameterNames, parameterJavaTypes, jsonNode);
	}

	private Class<?> resolveStoreClientClass(StoreOperations storeOperations) {
		return ((StoreClientHolder) storeOperations).getStoreClient().getClass();
	}

	private Object[] resolveParameterValues(String[] parameterNames, JavaType[] javaTypes, JsonNode jsonNode) {

		// populate params
		final int parameterSize = parameterNames.length;
		final Object[] params = new Object[parameterSize];

		for (int i = 0; i < parameterSize; i++) {
			final JavaType javaType = javaTypes[i];
			final String parameterName = parameterNames[i];

			if (jsonNode.has(parameterName)) {
				final String subJson = jsonNode.get(parameterName).toString();
				try {
					final Object param = this.objectMapper.readValue(subJson, javaType);
					params[i] = param;
				} catch (IOException e) {
					final String message =
							e.getMessage() + ". parameter=[" + parameterName + "] json=[" + subJson + "]";
					throw new EvernoteRestException(message, e);
				}
			} else {
				params[i] = null;  // if not included in json, then set as null  TODO: resolve default value??
			}
		}
		return params;
	}

}
