# ggqt — Greengrass Quick Templates

## TL;DR
<pre><b>ggqt</b> [-r <i>ggdir</i>] [-gtd <i>tdir</i>] [-g <i>group</i>] [-to <i>region</i>] [--dryrun | -dr] [--upload | -u] <i>files...</i></pre>

Option | Description
----- | -----
<nobr>-r _ggdir_</nobr>| The directory in which greengrass is installed
<nobr>-gtd _tdir_ </nobr>| The directory into which the generated templates (recipes & assets) are placed
<nobr>-g _group_ </nobr>| The group parameter for this deployment
<nobr>-to _region_</nobr> | Causes the constructed components to be uploaded to _region_, instead of being deployed locally.
<nobr>-dr &boxv; --dryrun</nobr> | Do not deploy the constructed component or upload it to a region
<nobr>-u</nobr> | Same as -to _defaultRegion_.
_files_ | A list of files to be bundled into a component.  All of the files become the artifacts of the component.  The first file is used to decide what template to use to construct the main recipe for the component, based mostly on the file's extension.  For example, a `.py` file will construct a recipe that executes the first file as a python program.  If no template can be found from the files extension, then if the first file is executable (as an `a.out` would be) it is executed directly; if it's first two bytes are `#!`, then it is executed as a shell script.

To install:
```
curl https://github.com/aws-greengrass/aws-greengrass-quick-templates/raw/main/install.sh|bash
```
## Description
Normally, to deploy something to a device, you have to construct recipe and artifact directories and populate them.  This has some powerful uses, but it can be cumbersome for simple projects. Fortunately, they can be automatically generated for you from program code using `ggqt`.  For example:
```
echo 'print("Hello from Python!")'>hello.py
ggqt hello.py
```
Will generate recipe and artifact directories and deploy them to the device.  If you want to inspect (and possibly reuse) the generated files, they will be in `~/gg2Templates/hello`.

By default, unless the `--dryrun` option is specified, once these are generated, they will be deployed:
```
sudo greengrass-cli --ggcRootPath ~/.greengrass deployment create \
  -r ~/gg2Templates/hello/recipes \
  -a ~/gg2Templates/hello/artifacts \
  -m hello=0.0.0
```

For a very simple case like this, the components name will be derived from the filename, in this case `hello`, and the version number will default to 0.0.0.  If the filename contains a version number, it will be taken from there.  For example, `hello-1.2.0.py` will have a version number of 1.2.0.

You can also embed component name and version information as comments in the source file itself.  For example, if `hello.lua` looked like this:
```
-- ComponentVersion: 1.1.0
-- ComponentName: OlaLua
print '¡Olá Lua!'
```
The components name and version would be OlaLua and 1.1.0.  The template for the app recipe will also cause an installation recipe for *lua* to be included.  After execution, gg2Templates will contain these files:
```
├── OlaLua
│   ├── artifacts
│   │   └── OlaLua
│   │       └── 1.1.0
│   │           └── hello.lua
│   └── recipes
│       ├── OlaLua-1.1.0.yaml
│       └── lua-5.3.0.yaml
```
And the generated recipe, `OlaLua-1.1.0.yaml` will look like this:
```
# 
---
RecipeFormatVersion: 2020-01-25
ComponentName: OlaLua
ComponentVersion: 1.1.0
ComponentDescription: Created for XXX on YYY from hello.lua
ComponentPublisher: XXX
ComponentDependencies:
  lua:
    VersionRequirement: ^5.1.0

Manifests:
  - Platform:
      os: all
    Lifecycle:
      Run:
        posix: ln -f -s -t . {artifacts:path}/*; lua hello.lua
        windows: whatever
```

If the first two characters of the file are "`#!`", then it will be treated as an executable script:
```
#! /usr/bin/perl
use warnings;
print("Hello, World!\n");
```

`.jar` files are handled similarly, except that component name and version are searched for in the manifest, and the manifest must have a main class specification.  Also, if there is a RECIPIES folder in the jar, the contents will be copied to the recipe directory.

## ToDo
* `--upload` to upload components to AWS Greengrass component registry
*  Generate/read zip files so developer laptop can bridge to embedded device
* Extract more information besides name and version.  eg. dependencies, periodicity and launch parameters.
* Read templates from the web, not just built into the cli app.
* and so much more...

## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for more information.

## License

This project is licensed under the Apache-2.0 License.

