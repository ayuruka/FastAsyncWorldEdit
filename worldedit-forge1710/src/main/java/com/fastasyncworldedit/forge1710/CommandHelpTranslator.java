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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Translates command, argument and flag descriptions in help output.
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

    private record Tables(Map<String, String> descriptions, Map<String, String> fixed) {

        boolean isEmpty() {
            return descriptions.isEmpty() && fixed.isEmpty();
        }

    }

    private CommandHelpTranslator() {
    }

    public static Component translate(Component component, Locale locale) {
        Tables tables = BY_LANGUAGE.computeIfAbsent(locale.getLanguage() + "-" + locale.getCountry(), key -> load(locale));
        return tables.isEmpty() ? component : translate(component, tables);
    }

    private static Tables load(Locale locale) {
        Map<String, String> descriptions = new HashMap<>();
        Map<String, String> fixed = new LinkedHashMap<>();
        ClassLoader loader = CommandHelpTranslator.class.getClassLoader();
        InputStream in = loader.getResourceAsStream("lang/" + locale.getLanguage() + "-" + locale.getCountry() + "/commands.json");
        if (in == null) {
            in = loader.getResourceAsStream("lang/" + locale.getLanguage() + "/commands.json");
        }
        if (in == null) {
            return new Tables(Collections.emptyMap(), Collections.emptyMap());
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
        return new Tables(descriptions, fixed);
    }

    private static String normalise(String text) {
        return text.strip().replaceAll("\\s+", " ");
    }

    private static Component translate(Component component, Tables tables) {
        Component result = component;
        if (result instanceof TextComponent text) {
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
                Component t = translate(child, tables);
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
        for (Map.Entry<String, String> ui : tables.fixed().entrySet()) {
            replaced = replaced.replace(ui.getKey(), ui.getValue());
        }
        return replaced.equals(content) ? null : replaced;
    }

}
