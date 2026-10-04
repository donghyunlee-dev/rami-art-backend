package com.ramiart.admin.blog.application;

import java.util.Map;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;

/** Strict allowlist canonicalizer for the stored rich-text format. */
@Component
public final class BlogContentSanitizer {
    private static final Set<String> TAGS = Set.of("p", "br", "strong", "em", "u", "s", "ul", "ol", "li",
            "blockquote", "h2", "h3", "a", "img");
    private static final Map<String, Set<String>> ATTRIBUTES = Map.of(
            "a", Set.of("href", "target", "rel"),
            "img", Set.of("src", "alt", "width", "height"));

    public String sanitize(String html) {
        if (html == null || html.isEmpty()) return html;
        Document parsed = Jsoup.parseBodyFragment(html, "");
        for (Element element : parsed.body().getAllElements()) {
            if (element == parsed.body()) continue;
            if (!TAGS.contains(element.normalName())) invalid();
            Set<String> allowed = ATTRIBUTES.getOrDefault(element.normalName(), Set.of());
            for (Attribute attribute : element.attributes()) {
                String name = attribute.getKey().toLowerCase();
                if (!allowed.contains(name)) invalid();
                if ((name.equals("href") || name.equals("src")) && !validUrl(attribute.getValue())) invalid();
                if ((name.equals("width") || name.equals("height"))
                        && !attribute.getValue().matches("[1-9][0-9]{0,3}")) invalid();
                if (name.equals("target") && !Set.of("_blank", "_self").contains(attribute.getValue())) invalid();
            }
        }
        String canonical = canonicalChildren(parsed.body());
        if (canonical.codePointCount(0, canonical.length()) > 100_000) {
            throw new BlogException("BLOG_CONTENT_TOO_LARGE");
        }
        return canonical;
    }

    private static String canonicalChildren(Element parent) {
        StringBuilder result = new StringBuilder();
        for (Node node : parent.childNodes()) {
            if (node instanceof TextNode text) {
                result.append(escapeText(text.getWholeText()));
            } else if (node instanceof Element element) {
                String tag = element.normalName();
                result.append('<').append(tag);
                for (String attribute : attributeOrder(tag)) {
                    if (element.hasAttr(attribute)) {
                        result.append(' ').append(attribute).append("=\"")
                                .append(escapeAttribute(element.attr(attribute))).append('"');
                    }
                }
                result.append('>');
                if (!Set.of("br", "img").contains(tag)) {
                    result.append(canonicalChildren(element)).append("</").append(tag).append('>');
                }
            }
        }
        return result.toString();
    }

    private static String[] attributeOrder(String tag) {
        return tag.equals("a") ? new String[] {"href", "target", "rel"}
                : new String[] {"src", "alt", "width", "height"};
    }

    private static boolean validUrl(String value) {
        return value.startsWith("https://") || value.startsWith("/media/");
    }

    private static String escapeAttribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    private static String escapeText(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void invalid() {
        throw new BlogException("BLOG_CONTENT_INVALID");
    }
}
