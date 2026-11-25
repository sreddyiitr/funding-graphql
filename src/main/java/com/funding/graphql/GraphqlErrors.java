package com.funding.graphql;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

/** Surface validation errors (unknown ids/actors) to clients instead of a generic INTERNAL_ERROR. */
@Component
public class GraphqlErrors extends DataFetcherExceptionResolverAdapter {
    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        if (ex instanceof IllegalArgumentException) {
            return GraphqlErrorBuilder.newError(env).errorType(ErrorType.BAD_REQUEST).message(ex.getMessage()).build();
        }
        return null;
    }
}
