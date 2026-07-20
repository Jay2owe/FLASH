package flash.pipeline.ui.sandbox;

import ij.Menus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FijiCommandRegistry {
    private static volatile List<Command> cached;

    private FijiCommandRegistry() {
    }

    public static List<Command> allCommands() {
        List<Command> local = cached;
        if (local != null) return local;
        synchronized (FijiCommandRegistry.class) {
            if (cached == null) {
                cached = Collections.unmodifiableList(loadCommands());
            }
            return cached;
        }
    }

    static void setForTests(Map<String, String> commands) {
        synchronized (FijiCommandRegistry.class) {
            cached = Collections.unmodifiableList(fromMap(commands));
        }
    }

    static void clearForTests() {
        synchronized (FijiCommandRegistry.class) {
            cached = null;
        }
    }

    private static List<Command> loadCommands() {
        Map<String, String> snapshot = new LinkedHashMap<String, String>();
        try {
            Hashtable commands = Menus.getCommands();
            if (commands != null) {
                for (Object key : commands.keySet()) {
                    if (key == null) continue;
                    Object value = commands.get(key);
                    snapshot.put(String.valueOf(key), value == null ? "" : String.valueOf(value));
                }
            }
        } catch (Throwable ignored) {
            // Headless tests or too-early ImageJ startup: leave the snapshot empty.
        }
        return fromMap(snapshot);
    }

    private static List<Command> fromMap(Map<String, String> commands) {
        List<Command> out = new ArrayList<Command>();
        if (commands != null) {
            for (Map.Entry<String, String> entry : commands.entrySet()) {
                String name = entry.getKey() == null ? "" : entry.getKey().trim();
                if (name.length() == 0) continue;
                out.add(new Command(name, entry.getValue()));
            }
        }
        Collections.sort(out, new Comparator<Command>() {
            @Override
            public int compare(Command a, Command b) {
                int byName = a.name.compareToIgnoreCase(b.name);
                if (byName != 0) return byName;
                return a.name.compareTo(b.name);
            }
        });
        return out;
    }

    public static final class Command {
        public final String name;
        public final String className;

        Command(String name, String className) {
            this.name = name == null ? "" : name;
            this.className = className == null ? "" : className;
        }
    }
}
