package com.funding.graphql;

import graphql.scalars.ExtendedScalars;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

@SpringBootApplication
public class FundingGraphqlApplication {

    public static void main(String[] args) {
        SpringApplication.run(FundingGraphqlApplication.class, args);
    }

    @Bean
    RuntimeWiringConfigurer scalars() {
        return wiring -> wiring
                .scalar(ExtendedScalars.DateTime)
                .scalar(ExtendedScalars.Date)
                .scalar(ExtendedScalars.GraphQLBigDecimal)
                .scalar(ExtendedScalars.Json);
    }
}
