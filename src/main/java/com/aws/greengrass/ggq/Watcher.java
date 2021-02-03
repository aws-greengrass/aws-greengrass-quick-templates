/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.aws.greengrass.ggq;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class Watcher {
    private final TemplateCommand args;
    private final WatchService watchService = newWatchService();
    private final Path logs;
    Watcher(TemplateCommand cmd) {
        args = cmd;
        logs = Paths.get(args.getGgcRootPath()).resolve("logs");
    }
    private WatchService newWatchService() {
        try {
            return FileSystems.getDefault().newWatchService();
        } catch (IOException ex) {
            args.err("Watch failure: ", ex);
            return null;
        }
    }
    int watch() {
        try {
            logs.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            Files.list(logs).sorted()
                    .forEach(n -> get(n.toString()).readToEOF());
            while (true)
                try {
                WatchKey w = watchService.take();
                w.pollEvents().forEach(e -> {
//                    System.out.println("EVENT " + e.kind() + "  on "
//                            + e.context());
                    Path p = (Path) e.context();
                    Watched f = get(p.toString());
                    if (e.kind() == StandardWatchEventKinds.ENTRY_MODIFY)
                        f.readToEOF();
                    else f.close();
                });
                w.reset();
            } catch (InterruptedException ex) {
            }
        } catch (IOException ex) {
            args.err("Watch start failure: ", ex);
        }
        return 0;
    }
    private final Map<String, Watched> map = new HashMap<>();
    Pattern backupPattern = Pattern.compile("_\\d\\d\\d\\d_\\d\\d_\\d\\d");
    Watched get(String name) {
        return map.computeIfAbsent(name,
                n -> backupPattern.matcher(n).find() || !n.endsWith(".log")
                ? unwatched
                : new Watched(logs, name)
        );
    }
    static final Matcher logPattern = Pattern.compile(
            "(\\d\\d\\d\\d-\\d\\d[0-9-:.T]*Z) +\\[([^]]*)\\] +\\(([^)]*)\\) +([^:]*): +([^.]*)\\.? *(.*)\\. (\\{.*\\})")
            .matcher("");
    String lastService = "";
    static final Matcher commandPattern = Pattern.compile(
            "command=\\[\"(.*)\"\\]").matcher("");
    Watched last;
    final ObjectMapper json = new ObjectMapper()
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static String gets(Map m, String key) {
        if (m == null) return "";
        Object v = m.get(key);
        return v instanceof String ? (String) v
                : v == null ? ""
                        : v.toString();
    }
    private static String gets(Object m, String key) {
        return m instanceof Map ? gets((Map) m, key) : "";
    }
    private static Object geto(Object m, String key) {
        Object ret = m instanceof Map ? ((Map) m).get(key) : null;
        if(ret!=null && !ret.getClass().isArray()) System.out.println(key+": "+ret.getClass()+" "+ret);
        return ret;
    }
    private static String flatten(Object o) {
        if (o == null) return "null";
        if (o.getClass().isArray()) {
            StringBuilder sb = new StringBuilder();
            int limit = Array.getLength(o);
            for (int i = 0; i < limit; i++) {
                Object s = Array.get(o, i);
                if (s != null) {
                    if (sb.length() > 0)
                        sb.append(", ");
                    sb.append(flatten(s));
                }
            }
            return sb.toString();
        } else {
            String ret = String.valueOf(o);
            if(ret.startsWith("[\"")) {
                ret = ret.substring(2);
                if(ret.endsWith("\"]"))
                    ret = ret.substring(0,ret.length()-2);
            }
            return ret;
        }
    }
    private static String trimTo(String s, int len) {
        if (s == null) return "";
        if (s.length() > len) {
            if (s.endsWith(".log"))
                s = s.substring(0, s.length() - 4);
            if (s.length() > len) {
                int ldot = s.lastIndexOf('.');
                if (ldot >= 0) s = s.substring(ldot + 1);
                if (s.length() > len) s = s.substring(0, len);
            }
        }
        return s;
    }
    String lastsname;
    synchronized void receive(Watched from, String line) {
//        if (from != last) {
//            System.out
//                    .println("_____________________" + from.name + "_______________________");
//            last = from;
//        }
        if (line.startsWith("{"))
            try {
            Map err = json.readValue(line, Map.class);
            String eventType = gets(err, "eventType");
            String level = gets(err, "level");
            Object context = err.get("contexts");
            String serviceName = gets(context, "serviceName");
//            String state = gets(context, "currentState");
            String message = gets(err, "message");
            char tag = ' ';
            switch (eventType) {
                case "stderr": tag = ' ';
                    break;
                case "stdout": break;
                case "shell-runner-start":
                    tag = '%';
                    message = trimTo(gets(context, "scriptName"), 10) + ": "
                            + flatten(geto(context, "command"));
                    break;
                case "service-report-state":
                    tag = '>';
                    message = gets(context, "newState");
                    break;
                default:
                    switch (level) {
                        case "WARN": tag = '*';
                            break;
                        case "ERR": tag = '!';
                            break;
                        default: tag = 0;
                    }
            }
            if (tag != 0) {
                System.out.printf("%-10s %c %s\n",
                        trimTo(serviceName.equals(lastsname) ? "" : serviceName, 10),
                        tag, message);
                lastsname = serviceName;
            } else if (args.verbose) {
                System.out.println("===========");
                err.forEach((k, v) -> System.out
                        .println("\t" + k + ":\t" + v));
            }
        } catch (JsonProcessingException ex) {
            System.out.println(ex);
//                System.exit(0);
        } else if (logPattern.reset(line).matches()) {
            if (!notifiedJson) {
                notifiedJson = true;
                System.out
                        .println("ggq --watch only works when the log format is JSON,\n"
                                + "you can switch by ");
            }
        } else System.out.println("? " + line);
    }
    boolean notifiedJson = false;
    private class Watched {
        private final String name;
        private final Path path;
        private BufferedReader in = EOF;
        Watched(Path dir, String n) {
            name = n;
            path = dir.resolve(n);
        }
        public boolean isClosed() {
            return in == EOF;
        }
        public synchronized void close() {
            if (!isClosed()) {
                try {
                    in.close();
                } catch(Throwable t) { 
                }
                in = EOF;
            }
        }
        public synchronized BufferedReader in() {
            if (isClosed())
                try {
                return in = Files.newBufferedReader(path);
            } catch (IOException ex) {
                System.out.println("[Open error: " + ex + "]");
                return EOF;
            } else return in;
        }
        public synchronized void readToEOF() {
            BufferedReader lin = in();
//            System.out.println(name+": starting to read");
            String line;
            try {
                while ((line = lin.readLine()) != null)
                    receive(this, line);
            } catch (IOException ex) {
                System.out.println(name + ": err " + ex);
            }
        }
    }
    private final Watched unwatched = new Watched(Paths.get("/tmp"), "unwatched") {
        @Override
        public BufferedReader in() {
            return EOF;
        }
        @Override
        public void readToEOF() {
        }
    };
    private final BufferedReader EOF = new BufferedReader(new Reader() {
        @Override
        public void close() throws IOException {
        }
        @Override
        public int read(char[] arg0, int arg1, int arg2) throws IOException {
            return -1;
        }
        @Override
        public int read() {
            return -1;
        }
    }, 1);
}
