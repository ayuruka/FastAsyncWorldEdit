package com.fastasyncworldedit.forge1710;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sk89q.worldedit.util.formatting.text.Component;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.util.formatting.text.event.HoverEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Translates English text that WorldEdit sends without message keys: command, argument and flag descriptions in help
 * output ({@code commands.json}), and messages hard-coded in WorldEdit and its command framework, such as argument
 * errors ({@code messages.json}, see {@link #loadMessages}).
 *
 * <p>WorldEdit's command descriptions are plain English strings in annotations, not message keys, so they never pass
 * through the translation files. This replaces text components whose content is a known description with the
 * translation from {@code lang/<locale>/commands.json} (keys are the English text; whitespace is normalised because
 * text blocks lose their indentation at compile time). The {@code _fixed} object of that file holds help UI strings
 * that are built into longer texts ("Help for //set", "Usage: //set &lt;pattern&gt;") and are replaced inside text.</p>
 */
public final class CommandHelpTranslator {

    private static final Logger LOGGER = LogManager.getLogger("FAWE-Forge1710");
    private static final Map<String, Tables> BY_LANGUAGE = new ConcurrentHashMap<>();

    private record Tables(Map<String, String> descriptions, Map<String, String> fixed, List<Replacement> patterns,
                          Map<String, Map<String, String>> tails) {

        boolean isEmpty() {
            return descriptions.isEmpty() && fixed.isEmpty() && patterns.isEmpty() && tails.isEmpty();
        }

    }

    private record Replacement(Pattern pattern, String replacement) {

    }

    private CommandHelpTranslator() {
    }

    public static Component translate(Component component, Locale locale) {
        Tables tables = tables(locale);
        return tables.isEmpty() ? component : translate(component, tables);
    }

    /**
     * Translates a plain message (WorldEdit's string print methods), line by line.
     */
    public static String translate(String text, Locale locale) {
        Tables tables = tables(locale);
        if (tables.isEmpty() || text.isEmpty()) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String translated = lookup(lines[i], tables);
            if (translated != null) {
                lines[i] = translated;
            }
        }
        return String.join("\n", lines);
    }

    private static Tables tables(Locale locale) {
        return BY_LANGUAGE.computeIfAbsent(locale.getLanguage() + "-" + locale.getCountry(), key -> load(locale));
    }

    private static Tables load(Locale locale) {
        Map<String, String> descriptions = new HashMap<>();
        Map<String, String> fixed = new LinkedHashMap<>();
        List<Replacement> patterns = new ArrayList<>();
        Map<String, Map<String, String>> tails = new HashMap<>();
        loadMessages(locale, descriptions, fixed, patterns, tails);
        InputStream in = open(locale, "commands.json");
        if (in == null) {
            return new Tables(descriptions, fixed, patterns, tails);
        }
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonObject json = new JsonParser().parse(reader).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                if (entry.getKey().equals("_fixed")) {
                    for (Map.Entry<String, JsonElement> ui : entry.getValue().getAsJsonObject().entrySet()) {
                        fixed.put(ui.getKey(), ui.getValue().getAsString());
                    }
                    continue;
                }
                String english = entry.getKey();
                String translated = entry.getValue().getAsString();
                descriptions.put(normalise(english), translated);
                // Help output may render multi-line descriptions line by line.
                String[] englishLines = english.strip().split("\n");
                String[] translatedLines = translated.strip().split("\n");
                if (englishLines.length > 1 && englishLines.length == translatedLines.length) {
                    for (int i = 0; i < englishLines.length; i++) {
                        descriptions.putIfAbsent(normalise(englishLines[i]), translatedLines[i].strip());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read command help translations for {}", locale, e);
        }
        return new Tables(descriptions, fixed, patterns, tails);
    }

    @Nullable
    private static InputStream open(Locale locale, String file) {
        ClassLoader loader = CommandHelpTranslator.class.getClassLoader();
        InputStream in = loader.getResourceAsStream("lang/" + locale.getLanguage() + "-" + locale.getCountry() + "/" + file);
        return in != null ? in : loader.getResourceAsStream("lang/" + locale.getLanguage() + "/" + file);
    }

    /**
     * {@code messages.json}: messages written as English text in WorldEdit and its command framework rather than as
     * message keys. "exact" replaces a whole text piece, "fixed" replaces substrings, "patterns" are regular
     * expressions (Java replacement syntax, applied in order) for sentences with values in them. "tails" maps the
     * English text of a component to replacements for its children's text, for sentences split into pieces
     * ("Missing argument for " + "&lt;pattern&gt;" + ".").
     */
    private static void loadMessages(Locale locale, Map<String, String> exact, Map<String, String> fixed,
                                     List<Replacement> patterns, Map<String, Map<String, String>> tails) {
        InputStream in = open(locale, "messages.json");
        if (in == null) {
            return;
        }
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonObject json = new JsonParser().parse(reader).getAsJsonObject();
            if (json.has("exact")) {
                for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("exact").entrySet()) {
                    exact.put(normalise(entry.getKey()), entry.getValue().getAsString());
                }
            }
            if (json.has("fixed")) {
                for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("fixed").entrySet()) {
                    fixed.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            if (json.has("tails")) {
                for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("tails").entrySet()) {
                    Map<String, String> children = new HashMap<>();
                    for (Map.Entry<String, JsonElement> child : entry.getValue().getAsJsonObject().entrySet()) {
                        children.put(child.getKey(), child.getValue().getAsString());
                    }
                    tails.put(entry.getKey(), children);
                }
            }
            if (json.has("patterns")) {
                for (JsonElement element : json.getAsJsonArray("patterns")) {
                    patterns.add(new Replacement(Pattern.compile(element.getAsJsonArray().get(0).getAsString()),
                            element.getAsJsonArray().get(1).getAsString()));
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read message translations for {}", locale, e);
        }
    }

    private static String normalise(String text) {
        return text.strip().replaceAll("\\s+", " ");
    }

    private static Component translate(Component component, Tables tables) {
        Component result = component;
        Map<String, String> tail = null;
        if (result instanceof TextComponent text) {
            tail = tables.tails().get(text.content());
            String replacement = lookup(text.content(), tables);
            if (replacement != null) {
                result = text.content(replacement);
            }
        }
        HoverEvent hover = result.hoverEvent();
        if (hover != null && hover.value() != null) {
            Component value = translate(hover.value(), tables);
            if (value != hover.value()) {
                result = result.hoverEvent(HoverEvent.of(hover.action(), value));
            }
        }
        List<Component> children = result.children();
        if (!children.isEmpty()) {
            List<Component> translated = new ArrayList<>(children.size());
            boolean changed = false;
            for (Component child : children) {
                Component t = child;
                if (tail != null && child instanceof TextComponent childText && tail.containsKey(childText.content())) {
                    t = childText.content(tail.get(childText.content()));
                }
                t = translate(t, tables);
                changed |= t != child;
                translated.add(t);
            }
            if (changed) {
                result = result.children(translated);
            }
        }
        return result;
    }

    @Nullable
    private static String lookup(String content, Tables tables) {
        if (content.isBlank()) {
            return null;
        }
        String translated = tables.descriptions().get(normalise(content));
        if (translated != null) {
            // Keep surrounding whitespace, which help output uses for layout.
            int start = 0;
            while (start < content.length() && Character.isWhitespace(content.charAt(start))) {
                start++;
            }
            int end = content.length();
            while (end > start && Character.isWhitespace(content.charAt(end - 1))) {
                end--;
            }
            return content.substring(0, start) + translated + content.substring(end);
        }
        String replaced = content;
        for (Replacement pattern : tables.patterns()) {
            replaced = pattern.pattern().matcher(replaced).replaceAll(pattern.replacement());
        }
        for (Map.Entry<String, String> ui : tables.fixed().entrySet()) {
            replaced = replaced.replace(ui.getKey(), ui.getValue());
        }
        return replaced.equals(content) ? null : replaced;
    }

}
