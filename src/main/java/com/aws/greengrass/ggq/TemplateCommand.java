/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.aws.greengrass.ggq;

import com.vdurmont.semver4j.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;
import java.util.zip.*;
import org.apache.velocity.*;
import org.apache.velocity.app.*;
import org.apache.velocity.runtime.*;
import org.apache.velocity.runtime.resource.loader.*;

public class TemplateCommand {
    boolean dryrun;
    CloudOps cloud;
    String generatedTemplateDirectory = "~/gg2Templates";
    String group = null;
    List<String> files;
    List<Assignment> assignments;
    private Path recipeDir;
    private Path artifactDir;
    boolean verbose = false;
    String bucket;
    private Path genTemplateDir;
    private final ArrayList<String> params = new ArrayList<>();
    private Path localTemplateDir;
    private Path zippedArtifacts;
    private RecipieFile keyFile, thisFile,
            generatedRecipe;
    private String artifactURL;
    private final List<String> artifacts = new ArrayList<>();
    private String javaVersion = "11";
    private final Map<String, RecipieFile> recipes = new LinkedHashMap<>();
    public void run() {
        if (cloud != null) cloud.setBucket(bucket);
        genTemplateDir = Paths.get(deTilde(generatedTemplateDirectory));
        if (files.isEmpty())
            err("cli.tpl.files");
        scanFiles();
        if (keyFile == null)
            err("cli.tpl.files");
        assignments.forEach(a -> addConfig(a.key, a.value));
        build();
        if (cloud != null) doUpload();
        if (!dryrun) deploy();
    }
    private int scanFiles() {
        files.forEach(fn -> {
            if (fn.endsWith(".jar")) {
                if (keyFile == null)
                    harvestJar(fn);
                artifacts.add(fn);
            } else if (isRecipe(fn))
                addRecipe(fn, capture(Paths.get(fn)));
            else
                addArtifact(fn);
        });
        return 0;
    }
    static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
    private void addArtifact(String fn) {
        if (keyFile == null) {
            int colon = fn.indexOf(':');
            if (colon > 0) {
                // the parameter is something like docker:postgres
                // we turn it into postgres.docker, to pick up the docker template,
                // but there is no file, it's just a template with a parameter.
                keyFile = new RecipieFile(fn.substring(colon + 1) + "." + fn
                        .substring(0, colon), "", false);
                return;  // just a parameterized template, no file.
            } else {
                String body = null;
                try {
                    body = ReadFirst(Paths.get(fn).toUri().toURL());
                } catch (MalformedURLException ex) {
                    err("cli.tpl.erd", ex);
                }
                if (body != null)
                    keyFile = new RecipieFile(fn, body, false);
            }
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
                artifactDir = recipeDir.getParent().resolve("artifacts");
        } else if (artifactDir != null)
            recipeDir = artifactDir.getParent().resolve("recipes");
        else if (name != null) {
            artifactDir = genTemplateDir.resolve(name).resolve("artifacts");
            recipeDir = genTemplateDir.resolve(name).resolve("recipes");
        }
        Path versionedArtifactDir = artifactDir.resolve(name).
                resolve(version.toString());
        System.out
                .println("Artifacts in " + versionedArtifactDir + "\nRecipes in " + recipeDir);
        directoryMustBeClean(versionedArtifactDir);
        directoryMustBeClean(recipeDir);
        zipArtifacts();
        generateTemplate();
        recipes.values().forEach(body -> {
            try {
                body.injectArtifactRefs(
                        body == generatedRecipe || body == keyFile
                                ? artifactURL : null);
                body.write(recipeDir);
            } catch (Throwable ex) {
                err("cli.tpl.err", ex);
            }
        });
    }
    private void directoryMustBeClean(Path dir) {
        try {
            Files.list(dir).forEach(p -> {
                String T = p.toString();
                if (".".equals(T) || "..".equals(T)) {
                    System.out.println("SHOULD NOT HAPPEN " + dir + "   " + p);
                    System.exit(-1);
                }
                try {
                    Path fp = dir.resolve(p);
                    if (Files.isDirectory(fp))
                        directoryMustBeClean(fp);
                    Files.delete(fp);
                } catch (IOException ex) {
                }
            });
        } catch (IOException ex) {
            try {
                Files.deleteIfExists(dir);
            } catch (IOException ex1) {
            }
            try {
                Files.createDirectories(dir);
            } catch (IOException ex1) {
                err("cli.tpl.cnc", dir);
            }
        }
    }
    @SuppressWarnings("UseSpecificCatch")
    private void generateTemplate() {
        if (!keyFile.isRecipe) {
            // Generate the root recipe (eg in ggq hello.lua, it will be lua.yml)
            keyPath = Paths.get(keyFile.filename);
            thisFile = keyFile;
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
                generatedRecipe = addRecipe(keyFile.name + '-' + keyFile.version,
                        tls.toString());
            } catch (Throwable t) {
                err("cli.tpl.nft", t);
            }
            // Generate the recipies that need to be generated to support the root recipe
            String recipe;
            while ((recipe = toBeGenerated.poll()) != null)
                try {
                thisFile = addRecipe(recipe, null);
                StringWriter out = new StringWriter();
                getVelocityEngine()
                        .getTemplate("platforms/" + recipe, "UTF-8")
                        .merge(context, out);
                thisFile.setBody(out.toString());
            } catch (Throwable ex) {
                err("cli.tpl.erd", ex);
            }
        } else
            System.out.println("[ using provided recipe file ]"); //            ComponentRecipe r = getParsedRecipe();
    }
    private VelocityEngine velocityEngine;
    private final VelocityContext context = new VelocityContext();
    Path keyPath;
    private VelocityEngine getVelocityEngine() {
        if (velocityEngine == null) {
            velocityEngine = new VelocityEngine();
            velocityEngine.setProperty(RuntimeConstants.RESOURCE_LOADER,
                    "file,classpath");
            velocityEngine.setProperty("classpath.resource.loader.class",
                    ClasspathResourceLoader.class.getName());
            velocityEngine
                    .setProperty(RuntimeConstants.FILE_RESOURCE_LOADER_PATH,
                            localTemplateDir.toString());
//            velocityEngine.setProperty(RuntimeConstants.EVENTHANDLER_INCLUDE, IncludeRelativePath.class.getName());
            velocityEngine.init();
            context.put("name", keyFile.name);
            context.put("version", keyFile.version);
            context.put("publisher", !isEmpty(keyFile.publisher)
                    ? keyFile.publisher
                    : System.getProperty("user.name", "Unknown"));
            context.put("configuration", "");
            context.put("dependencies", "");
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
                files.forEach(f -> sb.append(' ').append(f));
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
                s = a.getValue("Build-Jdk-Spec");
                if (!isEmpty(s)) {
                    Matcher jv = Pattern.compile("([0-9]+).*").matcher(s);
                    if (jv.matches())
                        javaVersion = jv.group(1);
                }
                s = a.getValue("Build-Jdk");
                if (!isEmpty(s)) {
                    Matcher jv = Pattern.compile("([0-9]+)\\..*").matcher(s);
                    if (jv.matches())
                        javaVersion = jv.group(1);
                }
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
        Deque<String> args = new LinkedList<>();
        args.add("--ggcRootPath");
        args.add(getGgcRootPath());
        args.add("deployment");
        args.add("create");
        args.add("-r");
        args.add(recipeDir.toString());
        args.add("-a");
        args.add(artifactDir.toString());
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
        if (!dryrun && runCommand(args) != 0)
            err("cli.tpl.deploy");
    }
    public void zipArtifacts() {
        if (artifacts.isEmpty())
            zippedArtifacts = null;
        else try {  // create zip file of artifacts
            Path adir = artifactDir.resolve(keyFile.name)
                    .resolve(keyFile.version.toString());
            Files.createDirectories(adir);
            Path zip = adir.resolve(keyFile.name + ".zip");
//            for(Provider p:Security.getProviders()) {
//                System.out.println(">>> "+p);
//                p.entrySet().forEach(e->System.out.println("\t"+e.getKey()+":\t"+e.getValue()));
//            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try ( OutputStream out =
                    new DigestOutputStream(
                            Files.newOutputStream(zip,
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE,
                                    StandardOpenOption.TRUNCATE_EXISTING),
                            md);  ZipOutputStream zf = new ZipOutputStream(out)) {
                artifacts.forEach(name -> {
                    Path src = Paths.get(name);
                    ZipEntry ze = new ZipEntry(src.getFileName().toString());
                    try {
                        System.out.println("Writing " + ze.getName());
                        zf.putNextEntry(ze);
                        Files.copy(src, zf);
                        zf.closeEntry();
                    } catch (IOException ex) {
                        err("cli.tpl.err", ex);
                    }
                });
                zf.close();
                // We now have a zip file and it's hash.
                String basename = toString(md.digest());
                String artifactName = basename + ".zip";
                zippedArtifacts = zip.getParent()
                        .resolve(artifactName);
                Files.move(zip, zippedArtifacts,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                System.out.println("Archive zip file: " + zippedArtifacts);
                artifactURL = "s3://"
                        + (cloud == null ? "localhost" : cloud.getBucket())
                        + "/" + artifactName;
                context.put("zbasefile", artifactName);
                context.put("zbase", basename);
            }
        } catch (IOException | NoSuchAlgorithmException t) {
            err("cli.tpl.err", t);
        }
    }
    public void doUpload() {
        if (zippedArtifacts != null)
            try {
            String s3key = zippedArtifacts.getFileName().toString();
            cloud.putObject(s3key, zippedArtifacts);
        } catch (Throwable ioe) {
            err("cli.tpl.err", ioe);
        }
        recipes.values().forEach(recipe -> {
            try {
                recipe.upload(cloud);
            } catch (IOException ex) {
                err("cli.tpl.err", ex);
            }
        });
    }
    private static final char[] hex = "0123456789ABCDEF".toCharArray();
    public static String toString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        if (bytes != null)
            for (byte b : bytes) sb.append(hex[(b >> 4) & 0xF])
                        .append(hex[b & 0xF]);
        return sb.toString();
    }
    private static final Pattern RECIPEPATTERN =
            Pattern.compile(".*\\.(yaml|yml|ggr)$");
    private static boolean isRecipe(String name) {
        return RECIPEPATTERN.matcher(name).matches();
    }
    private void err(String tag) {
        err(tag, null);
    }
    String err(String tag, Object aux) {
        if (verbose && aux instanceof Throwable)
            ((Throwable) aux).printStackTrace(System.out);
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
        if (name != null) {
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
    public void addConfig(String k, String v) {
        Map<String, String> config = (thisFile != null ? thisFile : keyFile).configuration;
        config.putIfAbsent(k, v);
    }
    private final Queue<String> toBeGenerated = new LinkedList<>();
    private final Set<String> platform = new HashSet<>();
    @SuppressWarnings("UseSpecificCatch")
    public class opHandlers {
        public String platform(String recipe) {
            if (!platform.contains(recipe)) {
                toBeGenerated.add(recipe);
                platform.add(recipe);
            }
            return "";
        }
        public String genConfig() {
            Map<String, String> m;
            if (thisFile == null || (m = thisFile.configuration).isEmpty())
                return "\n";
            StringBuilder sb = new StringBuilder();
            sb.append("\nComponentConfiguration:\n  DefaultConfiguration:\n");
            m.forEach((k, v) -> {
                if (k.endsWith("-env"))
                    k = k.substring(0, k.length() - 4);
                sb.append("    ").append(k).append(": ")
                        .append(v).append('\n');
            });
            return sb.toString();
        }
        public CharSequence genEnv() {
            Map<String, String> m;
//            m.put("WORKPATH", "{work:path}");
            if (thisFile == null || (m = thisFile.configuration).isEmpty())
                return "\n";
            StringBuilder sb = new StringBuilder();
            m.forEach((k, v) -> {
                if (k.endsWith("-env")) {
                    k = k.substring(0, k.length() - 4);
                    sb.append("        ").append(k)
                            .append(": '{configuration:/")
                            .append(k).append("}'\n");
                }
            });
            return sb.length() == 0 ? "\n" : "\n    setenv:\n" + sb;
        }
        public String addConfig(String k, String v) {
            TemplateCommand.this.addConfig(k, v);
            return "";
        }
        public String genDependencies() {
            Map<String, String> m;
            if (thisFile == null || (m = thisFile.dependencies).isEmpty())
                return "\n";
            StringBuilder sb = new StringBuilder();
            sb.append("\nComponentDependencies:\n");
            m.forEach((k, v) -> {
                boolean soft = false;
                if (v.endsWith("-soft")) {
                    soft = true;
                    v = v.substring(0, v.length() - 5);
                }
                sb.append("  ").append(k).append(":\n    VersionRequirement: ")
                        .append(v).append('\n');
                if (soft) sb.append("    DependencyType: SOFT\n");
            });
            return sb.toString();
        }
        public String addDependency(String name, String version) {
            (thisFile != null ? thisFile : keyFile).dependencies
                    .put(name, version);
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
        if (isDir(rootPath) == null) {
            // try some of the usual syspects
            rootPath = isDir(System.getenv("GGC_ROOT_PATH"));
            if (rootPath == null)
                rootPath = isDir(Main.getPreference("root", null));
            if (rootPath == null)
                rootPath = isDir("/greengrass/v2");
            if (rootPath == null)
                rootPath = isDir("/opt/GGv2");
            if (rootPath == null)
                rootPath = isDir("/usr/local/greengrass");
            if (rootPath == null)
                rootPath = isDir("/usr/local/opt/greengrass");
            if (rootPath == null)
                rootPath = isDir("/opt/greengrass");
            if (rootPath == null)
                rootPath = "$GGC_ROOT_PATH";
            else Main.putPreference("root", rootPath);
        }
        return rootPath;
    }
    String ggCli = null;
    public String getGGCli() {
        if (ggCli == null) {
            ggCli = canExecute(getGgcRootPath() + "/bin/greengrass-cli");
            if (ggCli == null)
                ggCli = canExecute("/usr/bin/greengrass-cli");
            if (ggCli == null)
                ggCli = canExecute("/usr/local/bin/greengrass-cli");
            if (ggCli == null)
                if (dryrun) ggCli = "xyzzy/greengrass-cli";
                else {
                    System.err.println("Can't find greengrass-cli");
                    System.exit(1);
                }
        }
        return ggCli;
    }
    public int runCommand(String... command) {
        return runCommand(null, command);
    }
    public int runCommand(LineReceiver lineReceiver, String... command) {
        LinkedList<String> ll = new LinkedList<>();
        for (String s : command) ll.add(s);
        return runCommand(lineReceiver, ll);
    }
    public int runCommand(Deque<String> command) {
        return runCommand(null, command);
    }
    public int runCommand(LineReceiver lineReceiver, Deque<String> command) {
        command.addFirst(getGGCli());
        command.addFirst("sudo");
        String[] nc = (String[]) command.toArray(new String[command.size()]);
        if (verbose) {
            System.out.append(dryrun ? "Dry Run:" : "Executing:");
            for (String s : nc)
                System.out.append(' ').append(s);
            System.out.println();
        }
        if (dryrun) return 0;
        try {
            ProcessBuilder pb = new ProcessBuilder(nc);
            Map<String, String> env = pb.environment();
            env.putIfAbsent("LOGNAME", "ggc");
            if (lineReceiver == null) lineReceiver = defaultLineReceiver;
            Process p = pb.start();
            Thread stdout = new CopyThread(p.getInputStream(), lineReceiver, false);
            Thread stderr = new CopyThread(p.getErrorStream(), lineReceiver, true);
            stdout.start();
            stderr.start();
            stdout.join();
            stderr.join();
            return p.waitFor();
        } catch (Throwable ex) {
            ex.printStackTrace(System.out);
            return -1;
        }
    }
    private static final LineReceiver defaultLineReceiver = (l, e) -> {
        if (!l.contains("INFO") && !l.contains(".awssdk."))
            System.out.println((e ? "? " : "  ") + l);
    };
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
