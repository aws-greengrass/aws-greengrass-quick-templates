/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.aws.greengrass.ggq;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public class Main {
    enum Dest {
        GTD, GROUP, ROOT, REGION, BUCKET, NONE, REMOVE
    }
    final String[] args;
    static final Set<String> templateSources = new LinkedHashSet<>();
    TemplateCommand tc = new TemplateCommand();
    boolean didSomethingUseful = false;
    public static void main(String[] cmd) {
        int ret = new Main(cmd).exec();
        if (ret != 0) showHelp();
        System.exit(ret & 0xFF);
    }
    public Main(String[] args) {
        this.args = args;
        getPreference("templates", templateSources);
    }
    int exec() {
        Dest dest = Dest.NONE;
        ArrayList<String> files = new ArrayList<>();
        ArrayList<Assignment> assignments = new ArrayList<>();
        boolean doWatch = false;
        for (String s : args)
            switch (dest) {
                case GTD:
                    tc.generatedTemplateDirectory = s;
                    dest = Dest.NONE;
                    break;
                case GROUP:
                    tc.group = s;
                    dest = Dest.NONE;
                    break;
                case ROOT:
                    tc.rootPath = s;
                    dest = Dest.NONE;
                    break;
                case REGION:
                    dest = Dest.NONE;
                    tc.cloud = CloudOps.of(s);
                    break;
                case BUCKET:
                    dest = Dest.NONE;
                    tc.bucket = s;
                    break;
                case REMOVE:
                    dest = Dest.NONE;
                    tc.runCommand("deployment", "create", "--remove", s);
                    didSomethingUseful = true;
                    break;
                case NONE:
                    switch (s) {
                        case "-b":
                        case "--bucket":
                            dest = Dest.BUCKET;
                            break;
                        case "-r":
                        case "--ggcRootPath":
                            dest = Dest.ROOT;
                            break;
                        case "-gtd":
                            dest = Dest.GTD;
                            break;
                        case "-g":
                        case "--group":
                            dest = Dest.GROUP;
                            break;
                        case "-dr":
                        case "--dryrun":
                            tc.dryrun = true;
                            break;
                        case "-l":
                        case "--list":
                            DeployedComponent.dump(tc);
                            didSomethingUseful = true;
                            break;
                        case "-pw":
                        case "--pw":
                            tc.runCommand("get-debug-password");
                            didSomethingUseful = true;
                            break;
                        case "-rm":
                        case "--remove":
                            dest = Dest.REMOVE;
                            break;
                        case "--to":
                        case "-to":
                            dest = Dest.REGION;
                            tc.dryrun = true;
                            break;
                        case "--upload":
                        case "-u":
                            tc.cloud = CloudOps.dflt();
                            tc.dryrun = true;
                            break;
                        case "--verbose":
                        case "-v":
                            tc.verbose = true;
                            break;
                        case "--watch":
                        case "-w":
                            doWatch = true;
                            break;
                        case "--help":
                        case "-h":
                            return 256; // sic
                        default:
                            if (s.startsWith("-")) {
                                System.out.println("Illegal argument: " + s);
                                return 1;
                            }
                            if (s.indexOf('=') > 0)
                                assignments.add(new Assignment(s));
                            else files.add(s);
                            break;
                    }
                    break;
            }
        if (doWatch)
            return new Watcher(tc).watch();
        if (!files.isEmpty()) {
            tc.files = files;
            tc.assignments = assignments;
            try {
                tc.run();
                return 0;
            } catch (Throwable t) {
                Throwable c = t;
                while (c.getCause() != null)
                    c = c.getCause();
                String m = c.getLocalizedMessage();
                if (m == null || m.length() == 0) m = c.toString();
                System.out.println(m);
                if (tc.verbose) t.printStackTrace(System.out);
                return 1;
            }
        } else if (!assignments.isEmpty())
            processAssignments(assignments);
        else if (!didSomethingUseful) {
            System.err.println("Usage: ggq files...");
            return 256;
        }
        return 0;
    }
    private static final String helpUrl = "https://github.com/aws-greengrass/"
            + "aws-greengrass-quick-templates/blob/main/README.md";
    public static void showHelp() {
        System.out.println("Help is available at " + helpUrl);
        try {
            java.awt.Desktop.getDesktop().browse(new URI(helpUrl));
        } catch (Throwable t) {
        }
    }
    private static final Path propFile = Paths.get(System
            .getProperty("user.home", "/tmp"), ".ggq.config");
    private static Properties prefs = null;
    public static synchronized String getPreference(String key, String dflt) {
        if (prefs == null) {
            prefs = new Properties();
            try (Reader in = Files.newBufferedReader(propFile)) {
                prefs.load(in);
            } catch (IOException ioe) {
            }
        }
        return prefs.getProperty(key, dflt);
    }
    public static synchronized void putPreference(String key, String value) {
        if (value == null || value
                .equals(getPreference(key, "3495723kjdwfg;@$"))) return;
        prefs.put(key, value);
        try (Writer out = Files
                .newBufferedWriter(propFile, StandardOpenOption.CREATE)) {
            prefs.store(out, "# Greengrass quick properties");
        } catch (IOException ioe) {
        }
    }
    private static final String csep = ">!<";
    private static <T extends Collection<String>> T getPreference(String key, T items) {
        String s = getPreference(key, "");
        if (!s.isEmpty())
            for (String e : s.split(csep))
                if (!e.isEmpty()) items.add(e);
        return items;
    }
//    private static <T extends Collection<String>> T putPreference(String key, T items) {
//        putPreference(key, String.join(csep, items));
//        return items;
//    }
    private void processAssignments(List<Assignment> assignments) {
        if (assignments != null && assignments.size() > 0) {
            didSomethingUseful = true;
            HashMap<String, StringBuilder> settings = new HashMap<>();
            assignments.forEach(s -> {
                if (s.component != null) {
                    StringBuilder comps = settings
                            .computeIfAbsent(s.component, n -> new StringBuilder());
                    if (comps.length() > 0) comps.append(",\n\t");
                    addKV(comps, s.key, s.value);
                } else
                    throw new IllegalArgumentException(s.key + ": not associated with a component");
            });
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            settings.forEach((k, v) -> {
                if (sb.length() > 1) sb.append(",\n");
                quote(sb, k);
                sb.append(": { \"MERGE\": {")
                        .append(v)
                        .append(" } }");
            });
            sb.append('}');
            Deque<String> cmd = new LinkedList<>();
            cmd.add("deployment");
            cmd.add("create");
            cmd.add("--update-config");
            cmd.add(sb.toString());
            settings.keySet().forEach(componentName -> {
                cmd.add("--merge");
                cmd.add(componentName + "=" + DeployedComponent
                        .of(componentName, tc).version);
            });
            if (tc.runCommand((l, e) -> {
                if (!l.contains("INFO") && !l.contains(".awssdk."))
                    System.out.println((e ? "? " : "  ") + l);
            }, cmd) != 0) {
                System.out.println("config update failed.");
                System.exit(1);
            }
        }
    }
    static void addKV(StringBuilder comps, String subKey, String value) {
        int dot = subKey.indexOf('.');
        if (dot < 0) {
            quote(comps, subKey);
            comps.append(": ");
            quote(comps, value);
        } else {
            quote(comps, subKey.substring(0, dot));
            comps.append(": {");
            addKV(comps, subKey.substring(dot + 1), value);
            comps.append(" }");
        }
    }
    private static final char[] hex = "0123456789ABCDEF".toCharArray();
    static StringBuilder quote(StringBuilder sb, String str) {
        final int len = str.length();
        char c;
        sb.append('"');
        for (int i = 0; i < len; i++)
            switch (c = str.charAt(i)) {
                case '"': sb.append("\\\"");
                    break;
                case '\\': sb.append("\\\\");
                    break;
                case '\b': sb.append("\\b");
                    break;
                case '\f': sb.append("\\f");
                    break;
                case '\n': sb.append("\\n");
                    break;
                case '\r': sb.append("\\r");
                    break;
                case '\t': sb.append("\\t");
                    break;
                default: if (c >= 0xFF || Character.isISOControl(c))
                        sb.append("\\u")
                                .append(hex[(c >> 12) & 0xF])
                                .append(hex[(c >> 8) & 0xF])
                                .append(hex[(c >> 4) & 0xF])
                                .append(hex[(c) & 0xF]);
                    else sb.append(c);
                    break;
            }
        sb.append('"');
        return sb;
    }
}
