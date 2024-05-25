package gov.cdc.izgateway.ads.mockdex;

import gov.cdc.izgateway.configuration.AppProperties;
import lombok.Data;
import lombok.Getter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Data
@Configuration
@EnableScheduling
public class DexConfiguration {
    @Getter
	@Value("${dex.tus-upload-directory:temp/upload/tus}")
    private String tusUploadDirectory;

    @Getter
    @Value("${dex.app-upload-directory:temp/upload/app}")
    private String appUploadDirectory;

    @Getter
    private final String mode;

    // Present DEX OAuth methods do not require Content-Type header, since DEX uses Query Parameters
    @Value("${dex.oauth-in-query:false}")
	private boolean usingQueryParameters;
    
    @Value("${dex.report-progress:true}")
	private boolean reportingProgressEnabled;

    @Value("${dex.numRetries:2}")
	private int numRetries;
    
    public DexConfiguration(@Autowired AppProperties app) {
    	mode = app.getServerMode();
    }
}