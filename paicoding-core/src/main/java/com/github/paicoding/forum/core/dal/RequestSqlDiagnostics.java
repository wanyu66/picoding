package com.github.paicoding.forum.core.dal;

import com.github.paicoding.forum.core.cache.RequestIoStats;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Counts executed statements for both single and dynamic data sources, without SQL values. */
@Component
@ConditionalOnProperty(name = "observability.request-io.enabled", havingValue = "true")
@Intercepts({
        @Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class}),
        @Signature(type = StatementHandler.class, method = "update", args = {Statement.class})
})
public class RequestSqlDiagnostics implements Interceptor {
    private static final Pattern TABLE = Pattern.compile("(?i)\\b(?:from|into|update)\\s+([a-zA-Z0-9_`]+)");

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler handler = (StatementHandler) invocation.getTarget();
        Matcher table = TABLE.matcher(handler.getBoundSql().getSql());
        RequestIoStats.sql(table.find() ? table.group(1) : "other");
        return invocation.proceed();
    }
}
