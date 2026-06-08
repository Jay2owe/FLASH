package flash.pipeline.intelligence.identity;

import flash.pipeline.intelligence.MiniJson;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Serialises a {@link NamingGrammar} to/from JSON (via {@link MiniJson}) so a
 * lab can save a grammar and reuse it across studies
 * ({@code FLASH/Config/.settings/naming_grammars/<name>.json}). Pure
 * string&lt;-&gt;model; the file read/write wiring lives in the integration stage.
 */
public final class NamingGrammarCodec {

    private NamingGrammarCodec() {
    }

    public static String toJson(NamingGrammar grammar) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("name", grammar == null ? "" : grammar.name);
        List<Object> fields = new ArrayList<Object>();
        if (grammar != null) {
            for (FieldRule rule : grammar.rules) {
                fields.add(ruleToMap(rule));
            }
        }
        root.put("fields", fields);
        return MiniJson.write(root);
    }

    public static NamingGrammar fromJson(String json) throws IOException {
        Object parsed = MiniJson.parse(json);
        if (!(parsed instanceof Map)) {
            throw new IOException("Grammar JSON root must be an object.");
        }
        Map<?, ?> root = (Map<?, ?>) parsed;
        String name = str(root.get("name"));
        List<FieldRule> rules = new ArrayList<FieldRule>();
        Object fieldsObj = root.get("fields");
        if (fieldsObj instanceof List) {
            for (Object fo : (List<?>) fieldsObj) {
                if (fo instanceof Map) {
                    rules.add(mapToRule((Map<?, ?>) fo));
                }
            }
        }
        return new NamingGrammar(name, rules);
    }

    private static Map<String, Object> ruleToMap(FieldRule rule) {
        Map<String, Object> fm = new LinkedHashMap<String, Object>();
        fm.put("type", rule.type.name());
        if (rule.type == FieldRule.Type.CONDITION) {
            fm.put("axisLabel", rule.axisLabel);
        }
        if (rule.isCapture()) {
            fm.put("mode", "capture");
            fm.put("pattern", rule.capture.pattern());
        } else {
            fm.put("mode", "alias");
            List<Object> values = new ArrayList<Object>();
            for (ValuePattern vp : rule.values) {
                Map<String, Object> vm = new LinkedHashMap<String, Object>();
                vm.put("canonical", vp.canonical);
                List<Object> matches = new ArrayList<Object>();
                for (Pattern p : vp.patterns) {
                    matches.add(p.pattern());
                }
                vm.put("match", matches);
                values.add(vm);
            }
            fm.put("values", values);
        }
        return fm;
    }

    private static FieldRule mapToRule(Map<?, ?> fm) throws IOException {
        FieldRule.Type type;
        try {
            type = FieldRule.Type.valueOf(str(fm.get("type")));
        } catch (IllegalArgumentException e) {
            throw new IOException("Unknown field rule type: " + fm.get("type"), e);
        }
        String axisLabel = str(fm.get("axisLabel"));
        String mode = str(fm.get("mode"));
        if ("capture".equals(mode)) {
            return FieldRule.capture(type, axisLabel, str(fm.get("pattern")));
        }
        List<ValuePattern> values = new ArrayList<ValuePattern>();
        Object valsObj = fm.get("values");
        if (valsObj instanceof List) {
            for (Object vo : (List<?>) valsObj) {
                if (!(vo instanceof Map)) continue;
                Map<?, ?> vm = (Map<?, ?>) vo;
                String canonical = str(vm.get("canonical"));
                List<String> matches = new ArrayList<String>();
                Object msObj = vm.get("match");
                if (msObj instanceof List) {
                    for (Object mo : (List<?>) msObj) {
                        if (mo != null) matches.add(String.valueOf(mo));
                    }
                }
                values.add(new ValuePattern(canonical, matches));
            }
        }
        return FieldRule.alias(type, axisLabel, values);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
