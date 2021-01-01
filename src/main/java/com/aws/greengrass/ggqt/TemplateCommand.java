/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.aws.greengrass.ggqt;

import com.vdurmont.semver4j.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;
import org.apache.velocity.*;
import org.apache.velocity.app.*;
import org.apache.velocity.runtime.*;
import org.apache.velocity.runtime.resource.loader.*;

public class TemplateCommand {
    // see https://semver.org/#is-there-a-suggested-regular-expression-regex-to-check-a-semver-string
    private static final String officialSemverRegex =
            "^(?P<major>0|[1-9]\\d*)\\."
            + "(?P<minor>0|[1-9]\\d*)\\."
            + "(?P<patch>0|[1-9]\\d*)"
            + "(?:-(?P<prerelease>(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)"
            + "(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?"
            + "(?:\\+(?P<buildmetadata>[0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$";
    boolean dryrun;
    CloudOps cloud;
    String generatedTemplateDirectory = "~/gg2Templates";
    String group = null;
    String[] files;
    private String recipeDir;
    private String artifactDir;
    String bucket;
    private Path genTemplateDir;
    private final ArrayList<String> params = new ArrayList<>();
    private Path localTemplateDir;
    private RecipieFile keyFile;
    private final List<String> artifacts = new ArrayList<>();
    private String javaVersion = "11";
    private final Map<String, RecipieFile> recipes = new LinkedHashMap<>();
    public void run() {
        if (cloud != null) cloud.setBucket(bucket);
        genTemplateDir = Paths.get(deTilde(generatedTemplateDirectory));
        if (files == null || files.length == 0)
            err("cli.tpl.files");
        scanFiles();
        if (keyFile == null)
            err("cli.tpl.files");
        build();
        if (cloud != null) doUpload();
        if (!dryrun) deploy();
    }
    private int scanFiles() {
        for (String fn : files)
            if (fn.indexOf('=') > 0)
                // Assume that any argument with an = sign is a parameter.
                params.add(fn);
            else if (fn.endsWith(".jar")) {
                if (keyFile == null)
                    harvestJar(fn);
                artifacts.add(fn);
            } else if (isRecipe(fn))
                addRecipe(fn, capture(Paths.get(fn)));
            else
                addArtifact(fn, true);
        return 0;
    }
    static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
    private void addArtifact(String fn, boolean xinfo) {
        if (keyFile == null && xinfo) {
            String body = null;
            try {
                body = ReadFirst(Paths.get(fn).toUri().toURL());
            } catch (MalformedURLException ex) {
                err("cli.tpl.erd", ex);
            }
            if (body != null)
                keyFile = new RecipieFile(fn, body, false);
        }
        artifacts.add(fn);
    }
    private String ReadFirst(URL u) {
        if (u != null)
            try ( Reader in = new InputStreamReader(new BufferedInputStream(u
                .openStream()))) {
            StringBuilder sb = new StringBuilder();
            int c;
            int limit = 4000;
            while ((c = in.read()) >= 0 && --limit >= 0)
                sb.append((char) c);
            return sb.toString();
        } catch (IOException ioe) {
            err("cli.tpl.erd", ioe);
        }
        return "";
    }
    @SuppressWarnings("UseSpecificCatch")
    public void build() {
        String name = keyFile.name;
        Semver version = keyFile.version;
        if (genTemplateDir == null)
            genTemplateDir = Paths.
                    get(System.getProperty("user.home", "/tmp"), "gg2Templates");
        localTemplateDir = genTemplateDir.resolve("templates");
        if (recipeDir != null) {
            if (artifactDir == null)
                artifactDir = new File(new File(recipeDir).getParentFile(), "artifacts")
                        .toString();
        } else if (artifactDir != null)
            recipeDir = new File(new File(artifactDir).getParentFile(), "recipes")
                    .toString();
        else if (name != null) {
            artifactDir = genTemplateDir.resolve(name).
                    resolve("artifacts").toString();
            recipeDir = genTemplateDir.resolve(name).resolve("recipes").
                    toString();
        }
        generateTemplate();
        Path ad = Paths.get(artifactDir).resolve(name).
                resolve(version.toString());
        Path rd = Paths.get(recipeDir);
        System.out.println("Artifacts in " + ad + "\nRecipes in " + rd);
        try {
            Files.createDirectories(ad);
            Files.createDirectories(rd);
        } catch (IOException ex) {
            err("cli.tpl.err", ex);
        }
        artifacts.forEach(fn -> {
            Path src = Paths.get(fn);
            Path dest = ad.resolve(src.getFileName());
            try {
                Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                err("cli.tpl.cnc", fn);
            }
        });
        recipes.values().forEach(body -> {
            try {
                body.write(rd);
            } catch (Throwable ex) {
                err("cli.tpl.err", ex);
            }
        });
    }
    @SuppressWarnings("UseSpecificCatch")
    private void generateTemplate() {
        if (!keyFile.isRecipe) {
            keyPath = Paths.get(keyFile.filename);
            final String templateName =
                    keyFile.hashbang != null ? "hashbang.yml"
                  : Files.isExecutable(keyPath) ? "executable.yml"
                  : keyFile != null ? extension(keyFile.filename) + ".yml"
                  : err("cli.tpl.nbasis", null);
            try {
                StringWriter tls = new StringWriter();
                getVelocityEngine()
                        .getTemplate("templates/" + templateName, "UTF-8")
                        .merge(context, tls);
                addRecipe(keyFile.name + '-' + keyFile.version,
                        tls.toString());
            } catch (Throwable t) {
                err("cli.tpl.nft", keyFile);
            }
        } else
            System.out.println("[ using provided recipe file ]"); //            ComponentRecipe r = getParsedRecipe();
    }
    private VelocityEngine velocityEngine;
    private VelocityContext context;
    Path keyPath;
    private VelocityEngine getVelocityEngine() {
        if (velocityEngine == null) {
            velocityEngine = new VelocityEngine();
            context = new VelocityContext();
            velocityEngine.
                    setProperty(RuntimeConstants.RESOURCE_LOADER, "file,classpath");
            velocityEngine.
                    setProperty("classpath.resource.loader.class",
                            ClasspathResourceLoader.class.getName());
            velocityEngine.
                    setProperty(RuntimeConstants.FILE_RESOURCE_LOADER_PATH, localTemplateDir
                            .toString());
            velocityEngine.init();
            context.put("name", keyFile.name);
            context.put("version", keyFile.version);
            context.put("publisher", !isEmpty(keyFile.publisher)
                    ? keyFile.publisher
                    : System.getProperty("user.name", "Unknown"));
            String description;
            if (keyFile != null && !isEmpty(keyFile.description))
                description = keyFile.description;
            else {
                StringBuilder sb = new StringBuilder();
                sb.append("Created for ")
                        .append(System.getProperty("user.name"))
                        .append(" on ")
                        .append(DateTimeFormatter.ISO_INSTANT.format(Instant.
                                now()))
                        .append(" from");
                for (String f : files)
                    sb.append(' ').append(f);
                description = sb.toString();
            }
            context.put("description", description);
            context.put("file", keyPath.getFileName().toString());
            params.forEach(s -> { // copy params to velocity context
                String[] kv = s.split("=", 1);
                if (kv.length == 2)
                    context.put(kv[0], kv[1]);
            });
            ArrayList<String> fileArtifactNames = new ArrayList<>();
            artifacts.forEach(fn -> fileArtifactNames.
                    add(new File(fn).getName()));
            context.put("files", artifacts.toArray(new String[fileArtifactNames.
                    size()]));
            context.put("ctx", new opHandlers());
            if (keyFile.hashbang != null)
                context.put("hashbang", keyFile.hashbang);
            if (javaVersion != null)
                context.put("javaVersion", javaVersion);
        }
        return velocityEngine;
    }
    private void harvestJar(String pn) {
        try ( JarFile jar = new JarFile(new File(pn))) {
            Manifest m = jar.getManifest();
            if (m != null) {
                Attributes a = m.getMainAttributes();
                StringBuilder body = new StringBuilder();
                String s = a.getValue("ComponentVersion");
                if (!isEmpty(s))
                    body.append("ComponentVersion: ").append(s).append("\n");
                s = a.getValue("ComponentName");
                if (!isEmpty(s))
                    body.append("ComponentName: ").append(s).append("\n");
                s = a.getValue("Build-Jdk");
                Matcher jv = Pattern.compile("([0-9]+)\\..*").matcher(s);
                if (jv.matches())
                    javaVersion = jv.group(1);
                keyFile = new RecipieFile(pn, body.toString(), false);
            }
            jar.stream().forEach(e -> {
                String name = e.getName();
                if (name.startsWith("RECIPES/") && isRecipe(name))
                    try ( Reader in = new InputStreamReader(jar
                        .getInputStream(e))) {
                    addRecipe(e.getName(), capture(in));
                } catch (IOException ioe) {
                    err("cli.tpl.crj", e.getName());
                }
            });
        } catch (IOException ex) {
            err("cli.tpl.erd", pn);
        }
    }
    private void deploy() {
        ArrayList<String> args = new ArrayList<>();
        args.add("--ggcRootPath");
        args.add(getGgcRootPath());
        args.add("deployment");
        args.add("create");
        args.add("-r");
        args.add(recipeDir);
        args.add("-a");
        args.add(artifactDir);
        args.add("-m");
        args.add(keyFile.name + "=" + keyFile.version);
        if (!isEmpty(group)) {
            args.add("-g");
            args.add(group);
        }
        params.forEach(s -> {
            args.add("-p");
            args.add(s);
        });
        System.out.append(dryrun ? "DryRun" : "Executing").
                append(": greengrass-cli");
        args.forEach(s -> System.out.append(' ').append(s));
        System.out.println();
        if (!dryrun && runCommand(args.toArray(new String[args.size()])) != 0)
            err("cli.tpl.deploy");
    }
    public void doUpload() {
        recipes.forEach((name, body) -> {
            try {
                body.upload(cloud);
            } catch (Throwable ex) {
                err("cli.tpl.err", ex);
            }
        });
    }
    private static final Pattern RECIPEPATTERN = Pattern.
            compile(".*\\.(yaml|yml|ggr)$");
    private static boolean isRecipe(String name) {
        return RECIPEPATTERN.matcher(name).matches();
    }
    private void err(String tag) {
        err(tag, null);
    }
    private String err(String tag, Object aux) {
        String msg = ResourceBundle
                .getBundle("com.aws.greengrass.cli.CLI_messages")
                .getString(tag);
        if (aux != null)
            msg = msg + ": " + aux;
//        throw new CommandLine.ParameterException(spec.commandLine(), msg);
        throw new IllegalArgumentException(msg);
    }
    private RecipieFile addRecipe(String name, String body) {
        RecipieFile ret = null;
        if (name != null && body != null) {
            int sl = name.lastIndexOf('/');
            if (sl >= 0)
                name = name.substring(sl + 1);
            ret = new RecipieFile(name, body, true);
            if (keyFile == null)
                keyFile = ret;
            recipes.put(ret.name, ret);
        }
        return ret;
    }
    private String capture(Path in) {
        try ( Reader is = Files.newBufferedReader(in)) {
            return capture(is);
        } catch (IOException ex) {
            err("cli.tpl.erd", ex);
            return null;
        }
    }
    private String capture(Reader in) {
        if (in != null) {
            StringWriter out = new StringWriter();
            int c;
            try {
                while ((c = in.read()) >= 0)
                    out.write(c);
            } catch (IOException ex) {
                err("cli.tpl.erd", ex);
            }
            close(in);
            return out.toString();
        }
        return null;
    }
    @SuppressWarnings("UseSpecificCatch")
    private static void close(Closeable c) {
        if (c != null) try {
            c.close();
        } catch (Throwable t) {
        }
    }
    @SuppressWarnings("UseSpecificCatch")
    public class opHandlers {
        public String platform(String recipe) {
            try {
                StringWriter out = new StringWriter();
                getVelocityEngine()
                        .getTemplate("platforms/" + recipe, "UTF-8")
                        .merge(context, out);
                addRecipe(recipe, out.toString());
            } catch (Throwable ex) {
                err("cli.tpl.erd", ex);
            }
            return "";
        }
    }
    public static String extension(String f) {
        if (f == null)
            return "";
        int dot = f.lastIndexOf('.');
        if (dot <= 0)
            return "";
        return f.substring(dot + 1);
    }
    public static String chopExtension(String f) {
        if (f == null)
            return "";
        int slash = f.lastIndexOf('/');
        if (slash >= 0)
            f = f.substring(slash + 1);
        Matcher m = Pattern.compile("\\.[a-zA-Z][^.-]*$").matcher(f);
        if (m.find())
            f = f.substring(0, m.start());
        return f;
    }
    String rootPath = null;
    public String getGgcRootPath() {
        if (rootPath == null) {
            // try some of the usual syspects
            rootPath = isDir(System.getenv("GGC_ROOT_PATH"));
            if (rootPath == null)
                rootPath = isDir("/greengrass/v2");
            if (rootPath == null)
                rootPath = isDir("/opt/GGv2");
            if (rootPath == null)
                rootPath = isDir("/usr/local/greengrass");
            if (rootPath == null)
                rootPath = isDir("/opt/greengrass");
            if (rootPath == null)
                rootPath = "$GGC_ROOT_PATH";
        }
        return rootPath;
    }
    public int runCommand(String... command) {
        String[] nc = new String[command.length + 2];
        String exc = canExecute(getGgcRootPath() + "/bin/greengrass-cli");
        if (exc == null)
            exc = canExecute("/usr/bin/greengrass-cli");
        if (exc == null)
            exc = canExecute("/usr/local/bin/greengrass-cli");
        if (exc == null) {
            System.err.println("Can't find greengrass-cli");
            return -1;
        }
        nc[0] = "sudo";
        nc[1] = exc;
        System.arraycopy(command, 0, nc, 2, command.length);
        for (String s : nc)
            System.out.append(' ').append(s);
        System.out.println();
        try {
            return java.lang.Runtime.getRuntime().exec(nc).waitFor();
        } catch (InterruptedException | IOException ex) {
            ex.printStackTrace(System.out);
            return -1;
        }
    }
    private static String isDir(String d) {
        return d != null && new File(d).isDirectory() ? d : null;
    }
    private static String canExecute(String d) {
        return d != null && new File(d).canExecute() ? d : null;
    }
    private static final String HOME = System.getProperty("user.home", "/tmp");
    public String deTilde(String s) {
        return isEmpty(s) ? null : s.startsWith("~/") ? HOME + s.substring(1) : s;
    }
}
