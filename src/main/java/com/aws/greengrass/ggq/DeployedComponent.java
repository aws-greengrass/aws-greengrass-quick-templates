/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.aws.greengrass.ggq;

import java.util.*;
import java.util.function.*;

public class DeployedComponent {
    public String name, version, state, configuration;
    private DeployedComponent(String n) {
        name = n;
        version = "0.0.0";
        state = "missing";
        configuration = null;
    }
    private static Map<String, DeployedComponent> svcMap = null;
    public static DeployedComponent of(String name, TemplateCommand tc) {
        return map(tc).computeIfAbsent(name.toLowerCase(),
                k -> new DeployedComponent(name));
    }
    public static void forEach(TemplateCommand tc, Consumer<DeployedComponent> func) {
        map(tc).values().forEach(func);
    }
    private static int maxWidth;
    private static synchronized Map<String, DeployedComponent> map(TemplateCommand tc) {
        Map<String, DeployedComponent> m = svcMap;
        if (m == null) {
            svcMap = m = new LinkedHashMap<>();
            tc.runCommand((line, isError) -> {
                int colon = line.indexOf(':');
                if (colon > 4) {
                    String key = line.substring(0, colon);
                    String value = line.substring(colon + 1).trim();
                    switch (key) {
                        case "Component Name":
                            current = new DeployedComponent(value);
                            int len = value.length();
                            if (len > maxWidth) maxWidth = len;
                            svcMap.put(value.toLowerCase(), current);
                            break;
                        case "Version":
                            if (current != null) current.version = value;
                            break;
                        case "State":
                            if (current != null) current.state = value;
                            break;
                        case "Configuration":
                            if (current != null && !value.equals("null")
                                    && !value.equals("{}"))
                                current.configuration = value;
                            break;
                        default:
                            if (tc.verbose)
                                System.err.println(
                                        "Unexpect component list element: " + key);
                            break;
                    }
                }
            },
                    "component", "list");
        }
        return m;
    }
    private static DeployedComponent current = null;
    public static void dump(TemplateCommand tc) {
        String fmt = "%-" + maxWidth // Yes, I am this anal
                + "s %-10s %-10s\n";
        forEach(tc, ds -> System.out.printf(fmt,
                ds.name, ds.version, ds.state));
    }
}
