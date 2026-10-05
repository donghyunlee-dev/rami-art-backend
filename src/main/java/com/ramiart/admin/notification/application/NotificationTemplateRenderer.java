package com.ramiart.admin.notification.application;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class NotificationTemplateRenderer {
    private static final Pattern TOKEN = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]{0,39})}}");
    private static final Set<String> ALLOWED = Set.of("studentName", "guardianName", "className", "billingMonth", "amount", "studioName");

    public String render(String template, Map<String,String> variables) {
        if (template == null || template.length() > 4000 || variables == null || variables.size() > ALLOWED.size()
                || !ALLOWED.containsAll(variables.keySet())) throw new NotificationException("NOTIFICATION_TEMPLATE_INVALID");
        Matcher matcher = TOKEN.matcher(template);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = variables.get(name);
            if (!ALLOWED.contains(name) || value == null || value.length() > 500) throw new NotificationException("NOTIFICATION_TEMPLATE_INVALID");
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        if (rendered.length() > 4000 || rendered.toString().contains("{{") || rendered.toString().contains("}}"))
            throw new NotificationException("NOTIFICATION_TEMPLATE_INVALID");
        return rendered.toString();
    }
}
