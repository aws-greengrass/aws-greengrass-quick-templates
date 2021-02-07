# ggq — GreenGrass Quick development tool

## TL;DR
<pre><b>ggq</b> [--dryrun | -dr] [-g <i>group</i>] [-gtd <i>tdir</i>] [-pw] [-r <i>ggdir</i>] [-rm <i>comp</i>] [--upload | -u] [--watch | -w] key=value... <i>files...</i></pre>

Option&numsp;&numsp;&numsp;&numsp;&numsp;&numsp;&numsp;&numsp;&numsp;&numsp;&numsp;&numsp;&numsp;&numsp;&numsp;&numsp;&numsp;&numsp; | Description
------ | -----
`-dr` &boxv; `--dryrun` | Do not deploy the constructed component or upload it to a region
`-g` _group_| The group parameter for this deployment
`-gtd` _tdir_| The directory into which the generated templates (recipes & assets) are placed
`-pw` | print the userid and password necessary to use the local debug console.
`-r` _ggdir_| The directory in which greengrass is installed
`-rm` _comp_| Remove the named component.  Necessary if you've been testing locally, but now want to try deploying from the cloud.
`-u` &boxv; `--upload` | Causes the constructed components to be uploaded to the current region, instead of being deployed locally. (see below)
`-w` &boxv; `--watch` | Watches the log files and prints them to stdout in a dev-friendly format
_files..._ | A list of files to be bundled into a component.  All of the files become the artifacts of the component.  The first file is used to decide what template to use to construct the main recipe for the component, based mostly on the file's extension.  For example, a `.py` file will construct a recipe that executes the first file as a python program.  If no template can be found from the files extension, then if the first file is executable (as an `a.out` would be) it is executed directly; if it's first two bytes are `#!`, then it is executed as a shell script.
_key=value..._ | Sets the value of a configuration variable.

### To install:
```
curl -L https://github.com/aws-greengrass/aws-greengrass-quick-templates/raw/main/install.sh|bash
```

### Caveat Emptor
This tool is more of a &ldquo;labs&rdquo; project.  It does not get the same rigorous testing as the nucleus and is officially unsupported.  But it is an active project: issues and PRs will be responded to on a reasonable-effort basis.

## Description
Normally, to deploy something to a device, you have to construct recipe and artifact directories and populate them.  This has some powerful uses, but it can be cumbersome for simple projects. Fortunately, they can be automatically generated for you from program code using `ggq`.  For example:
```
echo 'print("Hello from Python!")'>hello.py
ggq hello.py
```
Will generate recipe and artifact directories and deploy them to the device.  If you want to inspect (and possibly reuse) the generated files, they will be in `~/gg2Templates/hello`.

By default, unless the `--dryrun` option is specified, once these are generated, they will be deployed by executing:
```
sudo greengrass-cli --ggcRootPath ~/.greengrass deployment create \
  -r ~/gg2Templates/hello/recipes \
  -a ~/gg2Templates/hello/artifacts \
  -m hello=0.0.0
```

For a very simple case like this, the components name will be derived from the filename, in this case `hello`, and the version number will default to 0.0.0.  If the filename contains a version number, it will be taken from there.  For example, `hello-1.2.0.py` will have a version number of 1.2.0.

You can also embed component information as comments in the source file itself.  For example, if `hello.lua` looked like this:
```lua
-- ComponentVersion: 1.1.0
-- ComponentName: OlaLua
-- ComponentDescription: Some fun with lua
-- ComponentPublisher: HeionymousBosch
-- ComponentConfiguration: message=¡Olá Lua!
print(os.getenv("message"))
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
And the generated recipe, `OlaLua-1.1.0.yaml` will look roughly like this:
```yaml
---
RecipeFormatVersion: 2020-01-25
ComponentName: OlaLua
ComponentVersion: 1.1.0
ComponentDescription: Some fun with lua
ComponentPublisher: HeionymousBosch
ComponentConfiguration:
  DefaultConfiguration:
    message: ¡Olá Lua!
ComponentDependencies:
  lua:
    VersionRequirement: ^5.1.0

Manifests:
  - Platform:
      os: all

Lifecycle:
  setenv:
     message: '{configuration:/message}'
  Run: ln -f -s -t . {artifacts:path}/*; lua hello.lua
```

If the first two characters of the file are "`#!`", then it will be treated as an executable script:
```
#! /usr/bin/perl
use warnings;
print("Hello, World!\n");
```

`.jar` files are handled similarly, except that component name and version are searched for in the manifest, and the manifest must have a main class specification.  Also, if there is a RECIPIES folder in the jar, the contents will be copied to the recipe directory.

## --upload
`ggq --upload ...` does the same recipe and artifact generation and collection as `ggq ...` except that instead of deploying the component to the device that the command is run on, it uploads them to the cloud.  The artifacts are collected together into a `.zip` file and uploaded to S3.  The recipes are uploaded as deployable components in the Greengrass V2 console.  In order to ensure that recipes have unique version numbers, `ggq` may override the version number from the recipe to be one patch level newer than the newest version.

The usual development pattern is to use `ggq ..` without the `--upload` option until the developer is happy with the new component, then `ggq --upload ...` to upload it to the cloud to deploy it to other devices.

## key=value

Updates configuration parameters for installed components.  Essentially an ease-of-use layer on `--update-config` from the greengrass cli.

Example | What it does
--- | ---
`ggq fmt=json` | Sets the log format to JSON.
`ggq ll=warn` | Sets the log level to WARN.  This is the same as `ggq aws.greengrass.Nucleus:logging.level=WARN`
`ggq jvm=-Xmx32m` | Sets the jvm maximum heap size to 32 megabytes.
`ggq hello:msg="Hello Toledo!"` | Sets the `msg` configuration variable in the component `hello` to _Hello Toledo!_

## ToDo
* Generate/read zip files so developer laptop can bridge to embedded device
* Extract more information besides name and version.  eg. dependencies, periodicity and launch parameters.
* Read templates from the web, not just built into the cli app.
* and so much more...

## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for more information.

## License

This project is licensed under the Apache-2.0 License.

