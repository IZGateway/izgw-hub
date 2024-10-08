package gov.cdc.izgateway.db;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.zaxxer.hikari.HikariDataSource;

import lombok.extern.slf4j.Slf4j;

/**
 * This configuration class contains the necessary annotations and methods
 * to access a JpaRepository.  It is only created if spring.database is set to
 * jpa or migrate in application.yml, or SPRING_DATABASE=jpa or migrate in the environment, 
 * or if none of these are present in application.yml or the environment.
 * 
 * @author Audacious Inquiry
 *
 */
@Slf4j
@ConditionalOnExpression(
		  "'${spring.database:}'.equals('') or '${spring.database:}'.equals('jpa') or '${spring.database:}'.equals('migrate')"
		)
@Configuration
@EntityScan(basePackages={"gov.cdc.izgateway.db.model"})
@EnableJpaRepositories(basePackages={"gov.cdc.izgateway.db.repository"})
public class JpaConfiguration {

	/**
	 * Creates the Data Source using a Hikari Connection Pool 
	 * @param properties	The data source properties
	 * @return	A new HikariDataSource
	 */
	@Bean
	@ConfigurationProperties("spring.datasource.configuration")
	public HikariDataSource dataSource(DataSourceProperties properties) {
		try {
			log.info("Initializing Data Source: {}", properties.getUrl());
			return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
		} finally {
			log.info("Database initialized");
		}
	}

	/**
	 * Creates a Data Source properties object from spring.datasource properties
	 * in application.yml
	 * 
	 * @return	The properties to use to initialize the data source.
	 */
	@Bean
	@Primary
	@ConfigurationProperties("spring.datasource")
	@Profile("!test")
	public DataSourceProperties dataSourceProperties() {
		return new DataSourceProperties();
	}

}
