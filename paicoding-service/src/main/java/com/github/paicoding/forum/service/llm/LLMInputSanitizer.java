package com.github.paicoding.forum.service.llm;

import cn.hutool.http.HtmlUtil;
import com.github.paicoding.forum.core.senstive.SensitiveService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class LLMInputSanitizer {
    private static final Pattern ACTIVE_CONTENT = Pattern.compile("(?is)<(script|style|iframe|object|embed|svg|math)[^>]*>.*?(?:</\\1\\s*>|$)");
    private static final Pattern HTML_COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)");
    private static final Pattern SECRET = Pattern.compile("(?i)\\b(?:sk|api[_-]?key|token|secret)[-_=: ]+[A-Za-z0-9._-]{8,}\\b");
    private static final Pattern PROMPT_INJECTION = Pattern.compile("(?i)(ignore|disregard|override).{0,30}(instruction|prompt|system)|忽略.{0,20}(指令|提示词|系统)|你现在是.{0,30}(系统|开发者)");

    @Autowired
    private SensitiveService sensitiveService;

    public String sanitizeArticleContent(String input, int maxChars) {
        String value = StringUtils.defaultString(input);
        value = HtmlUtil.unescape(value);
        value = ACTIVE_CONTENT.matcher(value).replaceAll(" ");
        value = HTML_COMMENT.matcher(value).replaceAll(" ");
        value = HtmlUtil.cleanHtmlTag(value);
        value = StringUtils.normalizeSpace(value);
        value = EMAIL.matcher(value).replaceAll("[REDACTED_EMAIL]");
        value = PHONE.matcher(value).replaceAll("[REDACTED_PHONE]");
        value = SECRET.matcher(value).replaceAll("[REDACTED_SECRET]");
        value = PROMPT_INJECTION.matcher(value).replaceAll("[REMOVED_PROMPT_INJECTION]");
        value = sensitiveService.replace(value);
        int limit = Math.max(1, maxChars);
        int codePoints = value.codePointCount(0, value.length());
        return codePoints <= limit ? value : value.substring(0, value.offsetByCodePoints(0, limit));
    }
}
