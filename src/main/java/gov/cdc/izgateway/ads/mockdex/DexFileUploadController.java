package gov.cdc.izgateway.ads.mockdex;

import com.fasterxml.jackson.annotation.JsonIgnore;

import gov.cdc.izgateway.ads.ADSErrorResponse;
import gov.cdc.izgateway.logging.RequestContext;
import gov.cdc.izgateway.logging.event.EventIdMdcConverter;
import gov.cdc.izgateway.logging.markers.Markers2;
import gov.cdc.izgateway.model.RetryStrategy;
import gov.cdc.izgateway.security.AccessControlRegistry;
import gov.cdc.izgateway.security.ClientTlsSupport;
import gov.cdc.izgateway.security.Roles;
import gov.cdc.izgateway.security.oauth.AccessToken;
import gov.cdc.izgateway.security.oauth.ErrorObject;
import gov.cdc.izgateway.security.oauth.ExternalTokenStore;
import gov.cdc.izgateway.security.oauth.OAuthException;
import gov.cdc.izgateway.soap.fault.FaultSupport;
import gov.cdc.izgateway.soap.fault.UnexpectedExceptionFault;
import gov.cdc.izgateway.soap.fault.UnknownDestinationFault;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import me.desair.tus.server.TusFileUploadService;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

import org.slf4j.MDC;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.security.RolesAllowed;


import javax.xml.ws.http.HTTPException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

@Slf4j
@RestController
@RolesAllowed({ Roles.INTERNAL, Roles.ADMIN })
@RequestMapping({"/rest"})
@Lazy(false)

public class DexFileUploadController {

    private final Path appUploadDirectory;
    private final TusFileUploadService uploadService;
    private final Path tusUploadDirectory;
    private final DexConfiguration config;
    
    private Object realm;

    private ExternalTokenStore tokenStore = new ExternalTokenStore(null, null, null);
    
    @SuppressWarnings("serial")
    public static class AuthenticationException extends Exception {
        private final ErrorObject err;
        private final String token;
        public AuthenticationException(ErrorObject err, String token) {
            super("Authentication Error");
            this.err = err;
            this.token = token;
        }
        @JsonIgnore String getToken() {
        	return token;
        }
        
        @JsonIgnore 
        private ErrorObject getOAuthError() {
            return err;
        }
    }

    public DexFileUploadController(DexConfiguration dexConfig, AccessControlRegistry registry, ClientTlsSupport tlsSupport) {
    	config = dexConfig;
        tusUploadDirectory = Path.of(dexConfig.getTusUploadDirectory());
        appUploadDirectory = Path.of(dexConfig.getAppUploadDirectory());
        try {
            Files.createDirectories(tusUploadDirectory);
            Files.createDirectories(appUploadDirectory);
        } catch (IOException e) {
            log.error(Markers2.append(e), "Cannot create upload directory");
        }
        uploadService = new TusFileUploadService().withStoragePath(dexConfig.getTusUploadDirectory()).withUploadUri("/rest/upload/dex");//latest TUS version
        tokenStore.setUsingQueryParameters(config.isUsingQueryParameters());
        tokenStore.setNumRetries(dexConfig.getNumRetries());
        tokenStore.setTlsSupport(tlsSupport);
        //registry.register(this, "/rest");  /*Can we change this to below*/
        registry.register(this);

        Runtime.getRuntime().addShutdownHook(new Thread()
        {
            @Override
            public void run()
            {
                shutdown();
            }
        });
    }
    
    private void shutdown() {
    	if (tusUploadDirectory != null) {
    		removeAllFiles(tusUploadDirectory);
    	}
    	if (appUploadDirectory != null) {
    		removeAllFiles(appUploadDirectory);
    	}
    }
    
    private void removeAllFiles(Path path) {
    	File folder = path.toFile();
    	if (folder.isDirectory()) {
    		for (File f: FileUtils.listFiles(folder, null, false)) {
    			try {
    				f.delete();  // NOSONAR: We don't care if delete failed
    			} catch (Exception ex) {
    				// Silently ignore deletion exceptions.
    			}
    		}
    	}
	}

	@RequestMapping(value = { "/upload/oauth", "/upload/oauth/refresh" }, method = { RequestMethod.POST, RequestMethod.GET})
    public ResponseEntity<AccessToken> handleOauthPost(
    	HttpServletRequest servletRequest,
    	@RequestParam(required=false) String username,
    	@RequestParam(required=false) String password,
    	@RequestParam(name="refresh_token", required=false) String refreshToken,
    	@RequestParam(name="grant_type", defaultValue="password") String grantType,
    	@RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType
    	
    ) throws OAuthException {
        // Disable Logging
    	RequestContext.disableTransactionDataLogging();

        if (!config.isUsingQueryParameters() && !"application/x-www-form-urlencoded".equals(contentType)) {
            throw new HTTPException(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
        }
        
        String message = "";
        switch (grantType) {
        case "refresh_token":
        	if (StringUtils.isBlank(refreshToken)) {
        		message = "refresh_token parameter cannot be blank, empty or missing.";
        	}
        	break;
        case "password":
            if (StringUtils.isBlank(username)) {
                message = "username parameter cannot be blank, empty or missing.";
            }
            if (StringUtils.isBlank(password)) {
            	message = "password parameter cannot be blank, empty or missing.";
            }
        	break;
        default:
            message = "grant_type must be equal to password or refresh_token.";
        }
        if (message.length() > 0) {
            throw new OAuthException("invalid_request", String.format("OAuth Protocol Error: %s%nParameters: %s", message, parameterMapToString(servletRequest.getParameterMap())));
        }

        AccessToken accessToken = null;

        // Regardless of grant type, just create a new token.
        accessToken = tokenStore.createToken();
        
        log.debug("Created Token: {}", accessToken.getAccessToken());
        
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, "application/json");
        headers.add(HttpHeaders.CACHE_CONTROL, "no-store");
        headers.add(HttpHeaders.PRAGMA, "no-cache");
        
        return new ResponseEntity<>(accessToken, headers, HttpServletResponse.SC_CREATED);
    }
    
    private String parameterMapToString(Map<String, String[]> parameterMap) {
    	TreeMap<String, String> t = new TreeMap<>();
    	for (Entry<String, String[]> e : parameterMap.entrySet()) {
    		String value = "[Hidden]";
    		if (!"password".equals(e.getKey()) || e.getKey().contains("token")) {
    			String[] a = e.getValue();
    			value = a.length > 0 ? a[0] : null;
    		}
    		t.put(e.getKey(), value);
    	}
		return t.toString();
	}

	@ExceptionHandler(OAuthException.class)
    ResponseEntity<ErrorObject> handleOAuthException(HttpServletRequest req, HttpServletResponse resp, OAuthException ex) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        return new ResponseEntity<>(ex.getOAuthError(), headers, HttpServletResponse.SC_UNAUTHORIZED);
    }
    
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ErrorObject> handleAuthenticationException(
        HttpServletRequest req, HttpServletResponse resp, AuthenticationException ex
    ) {
        HttpHeaders headers = new HttpHeaders();
        String value = String.format(
            "Bearer realm=%s, error=%s, error_detail=%s",
            realm, ex.getOAuthError().getError(), ex.getOAuthError().getErrorDescription()
        );
        headers.add(HttpHeaders.WWW_AUTHENTICATE, value);
        log.info("Authentication Exception {} on Token: {}", value, ex.getToken());
        return new ResponseEntity<>(ex.getOAuthError(), headers, HttpServletResponse.SC_UNAUTHORIZED);
    }
    
    @ExceptionHandler(Exception.class)
    ResponseEntity<? extends Object> handleException(HttpServletRequest req, HttpServletResponse resp, Exception ex) {
        HttpHeaders headers = new HttpHeaders();
        
        if (ex instanceof OAuthException oaex) {
            return handleOAuthException(req, resp, oaex);
        }
        if (ex instanceof AuthenticationException aex) {
        	return handleAuthenticationException(req, resp, aex);
        }
        
        ADSErrorResponse err = null;
        String eventId = MDC.get(EventIdMdcConverter.EVENT_ID_MDC_KEY);

        if (ex instanceof FaultSupport f) {
           err = new ADSErrorResponse(f, eventId);
        } else if (ex instanceof ServletRequestBindingException) {
            err = new ADSErrorResponse(new UnexpectedExceptionFault("Servlet Binding Exception", ex, null), eventId);
            err.setRetryStrategy(RetryStrategy.CORRECT_MESSAGE);
        } else if (ex instanceof HTTPException hex && hex.getStatusCode() == HttpServletResponse.SC_UNAUTHORIZED) {
            err = new ADSErrorResponse(new UnexpectedExceptionFault("Not Authorized", ex, null), eventId);
            headers.add(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            err.setRetryStrategy(RetryStrategy.CORRECT_MESSAGE);
        } else {
            err = new ADSErrorResponse(new UnexpectedExceptionFault(ex, null), eventId);
        }

        HttpStatus status;
        if (ex instanceof UnknownDestinationFault) {
            status = HttpStatus.NOT_FOUND;
        } else {
        	status = err.getRetryStrategy().getStatus();
        }

        log.error(Markers2.append(ex), "{}", ex.getMessage());
        return new ResponseEntity<>(err, headers, status);
    }

    
    @RequestMapping(value = { "/upload/dex", "/upload/dex/**" }, method = { RequestMethod.POST, RequestMethod.PATCH,
        RequestMethod.HEAD, RequestMethod.DELETE, RequestMethod.GET, RequestMethod.OPTIONS })
    public void upload(HttpServletRequest servletRequest, HttpServletResponse servletResponse) throws Exception {
        // Disable Logging
        RequestContext.disableTransactionDataLogging();
        
    	// OPTIONS shouldn't require a token, it's just a status check.
        String method = servletRequest.getMethod();
        if (!"OPTIONS".equals(method)) {
        	String token = null;
            try {
            	token = getBearerToken(servletRequest);
                tokenStore.getAccess(token);
            } catch (OAuthException ex) {
                throw new AuthenticationException(ex.getOAuthError(), token);
            }
        }
        
        this.uploadService.process(servletRequest, servletResponse);

        String uploadURI = servletRequest.getRequestURI();

        UploadInfo uploadInfo = null;
        try {
            uploadInfo = this.uploadService.getUploadInfo(uploadURI);
        } catch (IOException | TusException e) {
            log.error(Markers2.append(e), "get upload info");
            throw e;
        }

        if (uploadInfo != null && !uploadInfo.isUploadInProgress()) {
            try (InputStream is = this.uploadService.getUploadedBytes(uploadURI)) {
                Path output = this.appUploadDirectory.resolve(uploadInfo.getFileName());
                Files.copy(is, output, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | TusException e) {
                log.error(Markers2.append(e), "get uploaded bytes");
                // If an error occurs copying in mock, this is not an essential failure of the service.
                return;
            }

            try {
                this.uploadService.deleteUpload(uploadURI);
            } catch (IOException | TusException e) {
                // If an error occurs deleting data in mock, this is not an essential failure of the service.
                log.error(Markers2.append(e), "delete upload");
            }
        }
    }

    private String getBearerToken(HttpServletRequest servletRequest) {
        String bearerToken = servletRequest.getHeader(HttpHeaders.AUTHORIZATION);
        String[] parts;
        if (bearerToken != null) {
            bearerToken = bearerToken.trim();
            parts = bearerToken.split("\\s+");
            if ("Bearer".equals(parts[0]) && parts.length > 1) {
                return parts[1];
            }
        }
        throw new HTTPException(HttpServletResponse.SC_UNAUTHORIZED);
    }

    // Check every two hours for cleanup.
    @Scheduled(fixedDelayString = "PT1H")
    private void cleanup() {
        Path locksDir = this.tusUploadDirectory.resolve("locks");
        if (Files.exists(locksDir)) {
            try {
                this.uploadService.cleanup();
            } catch (IOException e) {
                log.error(Markers2.append(e), "error during cleanup");
            }
        }
        
    }

}