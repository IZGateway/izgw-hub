package gov.cdc.izgateway.hub;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import org.apache.commons.lang3.time.DateFormatUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import gov.cdc.izgateway.common.BadRequestException;
import gov.cdc.izgateway.common.ResourceNotFoundException;
import gov.cdc.izgateway.logging.RequestContext;
import gov.cdc.izgateway.soap.fault.Fault;
import gov.cdc.izgateway.soap.fault.UnknownDestinationFault;
import jakarta.servlet.http.HttpServletRequest;
import software.amazon.awssdk.utils.StringUtils;

/**
 * This class is used to support ExceptionHandling for controllers
 */

@ControllerAdvice
public class ExceptionHandling {
    /**
     * @param ex The exception to handle
     * @param request	The HTTP request
     * @return	The response entity
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleExceptions(
            Exception ex,
            HttpServletRequest request) {
    	HttpStatus status = HttpStatus.BAD_REQUEST;
        Map<String, Object> errors = new HashMap<>();
        errors.put("eventId", RequestContext.getEventId());
        errors.put("timestamp", DateFormatUtils.ISO_8601_EXTENDED_DATETIME_TIME_ZONE_FORMAT.format(System.currentTimeMillis()));
        errors.put("status", status.value());
        errors.put("error", ex.getMessage());
        errors.put("path", request.getRequestURI());
        if (ex instanceof MethodArgumentNotValidException manv) {
        	status = handleValidationException(errors, manv);
        } else if (ex instanceof BadRequestException brex) {
        	status = HttpStatus.BAD_REQUEST;
        } else if (ex instanceof ResourceNotFoundException rnfex) {
        	status = HttpStatus.NOT_FOUND;
        } else if (ex instanceof Fault fault) {
        	status = handleFault(errors, fault);
        } else if (ex instanceof ErrorResponse errorResponse) {
        	status = handleErrorResponse(errors, errorResponse);
        }
        return new ResponseEntity<>(errors, status);
    }

	private HttpStatus handleFault(Map<String, Object> errors, Fault fault) {
		putIfNotEmpty(errors, "detail", fault.getDetail());
		putIfNotEmpty(errors, "diagnostics", fault.getDiagnostics());
		putIfNotEmpty(errors, "summary", fault.getSummary());
		if (fault instanceof UnknownDestinationFault) {
			return HttpStatus.NOT_FOUND;
		}
		return fault.getRetry().getStatus();
	}

	private void putIfNotEmpty(Map<String, Object> errors, String key, String detail) {
		if (StringUtils.isEmpty(detail)) {
			return;
		}
		errors.put(key, detail);
	}

	private HttpStatus handleValidationException(Map<String, Object> errors,
			MethodArgumentNotValidException manv) {
		// Get all the field errors in the inbound data
        List<FieldError> fieldErrors = manv.getBindingResult().getFieldErrors();
        if (!fieldErrors.isEmpty()) {
            String errorMessage = fieldErrors.stream()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .collect(Collectors.joining("; "));
            errors.put("message", errorMessage);
        }
        return HttpStatus.BAD_REQUEST;
	}
	

	private HttpStatus handleErrorResponse(Map<String, Object> errors, ErrorResponse errorResponse) {
		ProblemDetail detail = errorResponse.getBody();
		if (detail == null) {
			return HttpStatus.valueOf(errorResponse.getStatusCode().value());
		}
		putIfNotEmpty(errors, "message", detail.getTitle());
		putIfNotEmpty(errors, "detail", detail.getDetail());
		if (detail.getProperties() != null) {
			for (Entry<String, Object> e: detail.getProperties().entrySet()) {
				if (errors.get(e.getKey()) == null) { // Do NOT overwrite any value already set
					putIfNotEmpty(errors, e.getKey(), e.getValue().toString());
				}
			}
		}
		return HttpStatus.valueOf(detail.getStatus());
	}
}
