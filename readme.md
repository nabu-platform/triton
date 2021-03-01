# TODO

local aliases
-> currently all aliases are global which is nice
-> sometimes you want an environment specific one!
-> link it to the name of the server (e.g. bebat-dev1)

would be nice to support group as well
-> local aliases should work on group, rather than individual
-> grouped servers are assumed to be identically configured

file upload & download!

virtual file system implementation for Path (inputstreams etc are via path.getFileSystemProvider().newInputStream(path))
-> once this is done, i can implement nano & less (ship with the terminal)
can also allow for easy file dropping? e.g. create a temporary folder, drop files in it, return to terminal which is prompted to "continue uploading files [Y/n]"
	-> if y, all the files in the folder will be dropped there
	
how to figure out which methods need to be run on the client?
e.g. nano("thefile") needs to run on the client primarily, though you might pass in a variable that lives in the server context?
same for file upload, you want to cd() to the correct folder on the server, then do something like "upload()" or "download(thefile)" allowing you to exchange files

perhaps add callbacks much like input, but then specifically for text editing etc?

so nano would trigger a specific request (if the client is able) to text edit a file
-> at that point the current text has to be streamed to the client first?

any binary transfer is preceeded by the amount of bytes to be streamed
-> there is no "chunked" type alternative
-> when you stream a big file for editing, we take an md5 while streaming
	-> we only upload the file again if the md5 has changed


Should perhaps store "servers" in different keystore from "profiles"?
Otherwise, by connecting to a server, you also automatically give it access to connect to your machine?
Although this is (by default) in .triton-server and .triton-client respectively...
So maybe it's just fine.


-> show the actual server at the top (permanently?) rather than just the version of the triton server

have a "request access"? basically it just stores the crt in a request folder
-> can disable this in triton server (to prevent spamming)
-> can easily accept or reject proposals
-> rejected are also kept so we can keep track of rejections?


## Multiple Versions of Packages

This is primarily aimed at deployments etc where multiple versions can coexist and we can switch between them simply by relinking.

when scanning packages we can have the same package multiple times (different versions that is)
we need to know which one is active?
-> the folder structure will be <module>/<version> so based on the symbolic link we know which version is active
-> the other packages are not in the "installed" list nor active (from scripts perspective)
-> but you can ask for "alternatives" to an installed package which will list the available alternative installations
-> you can then "activate" an alternative.

when installing a package
-> 


- we now have package verification, we need to be able to create packages
	-> do this using triton itself
	-> create a package from a directory or sign an existing zip file
	
- test invalid zips etc, make sure they don't make it through

- when "installing" a package (can be from any url)
	-> first obviously check if valid, then check if an older version of the module has been installed
	-> if a newer version is installed, verify with the user that you want to downgrade
	-> uninstall previous version, for scripts we need to also unload the script repository and re-add it (then refresh)
-> when uninstalling a zip on the filesystem (which was unzipped)
	-> delete file by file as based on the previous zip file (rather than removing the entire directory)
	-> if a directory is empty after files have been removed, remove the directory itself as well
	
checked: for non-scripts, do we want to actually unzip so it can be used outside of triton?
-> e.g. software installation (nabu, application,...)

# Interaction

## Unsupervised

There are two interesting modes that triton (and glue) can operate in:

- interactive
- unsupervised

Triton allows you to switch between the two modes but it starts by default in interactive mode.

In interactive mode it will prompt the user for an answer when edge cases are detected and we want to make sure the user knows what he's doing.
In unsupervised mode however, there will be no prompt and instead triton will continue as best as possible on the chosen path.

Note that not everything can be done unsupervised.

## Aliases

If you have to repeat commands many times, either on the same server or cross server, there are generally two ways to solve this:

- install a new script on every server to automate that task
- create a local alias to cover it

A local alias is specific to you, no one else can see it or use it. It is available regardless of the server you are connected to.

To create a new alias, type something like this:

```
$ alias: myAlias
```

You will then be switched into a nano-based text editor where you can type a regular glue script.

If at a later point in time you then type:

```
$ myAlias
```

The client will actually send the script you defined. 

# File Access

## File Editing

If you want to edit a file on the server you are connected to, you can use the command "nano()". It is of course based on the primitive text editor that ships with most linux systems.

Unlike most triton features, file editing is only available if the client actively enables it, this means a telnet client will not be able to edit files and instead get feedback along the lines of: The attached console does not support file editing.

The terminal client does enable it and by default it uses the awesome nano implementation that ships with jline.

When you want to edit a file, you can type: 

```
nano("test.txt")
```

The server will at that point check if the client has registered the file editing capabilities and if so, start a negotiation with the client.
If the client agrees, the file will first be copied to the client into a tmp folder.

The text editor is then started on the local version of the file.
Once the text editor closes, the file is streamed back to the server.

Currently there is no pro-active check if the file actually changed during editing, this is an optimization planned for the future.

# Security

Security consists of two parts:

- authentication: who is connecting
- authorization: what can they do

This can be managed locally or remotely.

## Local Connection

A listener is added to port 5122 (triton.local.port=5122) which allows for direct access through either the console or telnet. This connection is not secured and only connections made from local addresses are accepted. 

Whoever connects to this port is considered to be an admin as they have access to the machine itself. It is advised to use SSH or something to access Triton over this port.

Example of a connection url for the console: ``ts://localhost:5122``
Example of a telnet connection:

```
$ telnet localhost 5122
```

You can disable the local port by setting the parameter ``triton.local.enabled=false`` when starting triton.

The local connection can be used to do initial setup of the machine and/or as the only point of access if layered over something like SSH.

By default the triton console will try to connect to ``ts://localhost`` which is the unsecure connection.
You can connect to a different host using the parameter ``triton.host`` or simply ``host``.

Note that if you fill in a host without specifying the scheme, triton will assume you want sts seeing as ts is only available to localhost.

## SSL

Note that certificates are used for both server and client authentication. The CN field is considered to be the identity while the certificate itself is the authentication.

Example of a connection url for the console: ``sts://localhost``.
You may be able to still connect to the secure endpoint using for example the openssl toolset but because client certificates are mandatory this might be slightly harder to set up.

### Server side

On port 5123 (triton.secure.port=5123) triton will start an SSL-secured equivalent of the other port, meaning that, once connected it behaves exactly the same but it is secured by SSL.

Unless centrally managed, every Triton server will generate a self signed certificate which will be used as its identity.
When users connect to this machine for the first time, they will be asked if they want to accept the certificate.

You can centralize the creation of the certificates with a central authority which means you can configure clients to automatically accept the certificates, this creates an additional layer of trust that is highly advised in enterprise setups.

### Client side

The Triton server will expect a client side certificate which authenticates the user. There is (currently) no username/password equivalent.

The client can have multiple identities, which is to say multiple certificates. When starting up the console the user can choose which identity to use to connect to a particular Triton server.

The certificates that the client has can be either self-signed or generated by a central entity.

If generated by a central entity, the root certificate can be installed on the triton server, allowing anyone that has a correctly signed certificate with no further action being taken. Note that this is only the "authentication" part, what you can do once you connect (if anything) is part of the authorization.

If the certificate is self signed, it has to be installed on the Triton server to allow access. This can be done by someone who already has access (and the correct authorization) or this can be done using the local access on port 5000.

#### You install it

Connect with your console to the local admin connection (e.g. layered over SSH).

Type ``allow``.

The console will at that point install the certificate of the current identity in the server it is connected to.

#### Someone else installs it

If someone else installs the certificate, these steps should be performed:

- the client who wants access starts up the console and types ``self``. This will output his certificate which he must give to the person who can access the machine
- the other person access the Triton instance and types this:

```
allow("<paste certificate here>")
```

Note that by default the certificate output is a multiline, you should add one tab to the entire content before pasting it in the console to get a correct multiline statement.

When a new certificate is added, the 5123 port will go offline temporarily and be restarted with the new SSL context, this is currently a limitation of how it is set up.

## Something you know

To add to the "something you have" (the certs), you can also add "something you know".
This is not enforced by the Triton server, instead it is the password that is used to securely store your certificate, meaning it is entirely handled by the client.

## Profiles

You can use multiple profiles for your triton installation, this is mostly interesting when using the triton client though it can also be used on the triton server.

The default profile is "triton-client" or "triton-server" depending on the mode. You can configure ``triton.profile`` or simply ``profile`` to choose a given profile.
Each profile has its own public/private key meaning you can switch between identities by choosing a different profile.

If there is no key yet for a given profile, one will be generated for your with a self signed certificate, you can manage the settings used in that certificate:

- triton.name: the CN of the certificate, this defaults to the name of your computer unless specified otherwise
- triton.organisation
- triton.organisationalUnit
- triton.locality
- triton.state
- triton.country

# Folder Layout

Triton needs a number of folders to operate, if you configure nothing, the defaults will be used.
The defaults are the triton client are located in a hidden folder ``.triton-client`` which resides in your home folder whereas the default for the server is ``.triton-server``.

## Configuration

Triton needs a configuration folder where it stores things like the keystore.
This can be configured using ``triton.folder.config`` and defaults to the root of the hidden folder.

## Scripts

Triton needs a folder to store and use glue scripts. This can be configured by setting ``triton.folder.scripts``.
It defaults to a subfolder "scripts" of the hidden folder.

# Packaging

The basic distributable is a zip. Inside that zip must be at least one folder, the name of the folder must match a folder configured in the folder layout.
For example if you want to distribute scripts, the zip file should have a root folder "scripts" and inside it, the actual scripts.
You can have multiple root folders that are installed to their relative locations.

Each zip file should contain a ``manifest.tr`` file.
The manifest is a key/value pair list structured like this:

```
key=value
key=value
```

There are two mandatory descriptive fields:

```
module=<module>
version=<version>
```

The module name must contain only lowercased letters and dashes.
The version must adhere to this format:

```
major.minor.patch
```

This can be for example "1.0.1".

The zip must also contain a file ``author.crt`` which is the certificate of the author. The CN of the author is considered to be the name of the author.
Note that the certificate must be trusted by the triton server as a package provider, either by installing it manually or by installing a trusted root certificate.

**TODO:** in the future we might need to allow for chains as well.

For every file available in the zip, a signature must be included in the manifest. The signature must be generated using the private key of the author.
Store the signature like this:

signature-<file_path_in_zip>=<signature>

If any file is unsigned or has a faulty signature, the entire zip is ignored.

## Create a signed package

An example of how we can create a simple signed zip, we log in with the triton console and type this:

```
$ content = zip(structure(scripts: structure(lambda("test.glue"): "echo('hello!')")))
$ write("/home/alex/tmp/test-unsigned.zip", content)
$ signed = sign(content, "test")
$ write("/home/alex/tmp/test-signed.zip", signed)
```

The first is an unsigned zip containing a single folder "scripts" and within it a single file "test.glue".
The second file we write is the same zip but signed with the default profile of the server.

This is the packaging profile, rather than the authentication profile. This is a separate set of keys and certificates.

An example of creating and installing a package:

```
# We create the zip in memory
$ content = zip(structure(scripts: structure(lambda("test.glue"): "echo('hello!')")))
# We sign it with our default profile
$ signed = sign(content, "test")
# We install the signed zip
$ install(signed)
# We validate that the package has been installed
$ installed()
[test@1.0.0]
# We try to call the script that we installed
$ test()
hello!
```

You can write the signed package to the file system and distribute it however you want. In the future there will be a central distribution point as well.

## Trusting an author

When you try to install a package that belongs to an author you do not yet trust, you can choose to trust the author.

# Interactive system commands

Some system commands like ``tail -f`` stay open until you kill them.

This means two things:

- we need to stream the output of the tail command continuously to the triton client
- the triton client must be able to signal the triton server to kill the process

The first requirement is not too hard but the second required a bit of creative problem solving...

The triton terminal will intercept kill signals and check if it is waiting for anything on the remote server. If so, it will not signal local kill but instead send a predetermined fixed string to the triton server.

The triton server will be on the lookout for that particular string and will terminate the process if it finds that. Any other content (apart from the signal) is sent to the process.

This is not ideal and can cause edge cases where the signal arrives in multiple pieces and is not recognized as such, or the process is killed without having processed all the input before the signal.

The triton server is also mixing the input and output of the socket in a number of scenario's, which should be OK in a single threaded environment but may lead to an I/O deadlock in case of multithreaded access or an unforeseen edge case.

As an additional failsafe, if you send a kill signal three times while it is in running mode, the terminal will exit, assuming it can't properly close down the process. This should kill the entire session on the triton server side as well.

# Custom Methods

Triton ships with a few additional methods of its own.

## User Management

- connected(): list all connected sessions
- disconnect(long id): disconnect the session with that id
- addUser(string cert): add the certificate of a user so they can connect
- users(): list all the trusted users
- removeUser(string name): remove a user

A user can print the certificate for a certain profile by typing ``self``, then send that certificate via third party protocol like email or chat.

For convenience (you can turn it off by setting ``store.untrusted`` to false), anyone who connects to the secure endpoint but is not trusted, has his certificate persisted for later evaluation.
You can check out the currently pending unhandled certificates using:

- pendingAuthentication(): list all currently pending certs

You can accept or reject the pending using the same addUser and removeUser methods described above, passing in the exact output of the pending.

Note that you should be *absolutely* sure who the certificate belongs to before accepting it. It is merely a convenience method to prevent having to manually send the `self` around.

The preferred method is:

- someone who is trusted logs in (can be over ssh to the local endpoint), he already checks the pending to see if there is anything waiting
- someone who is untrusted tries to connect
- the connected person rechecks pending, finds the new certificate and accepts it

Actions are coordinated via a third party protocol for example phone or chat.
Any process beyond that can be easily compromised by well informed actors.

For enterprise users it is strongly recommended to centralize user trust management via externally managed certificate authorities.

## Package management

### Authors

- addAuthor(string cert): add the certificate of a trusted author so packages can be installed
- authors(): list the trusted authors
- removeAuthor(string name): remove an author, this will also remove any packages installed by that author!
- authored(string name): list all the packages from a specific author

### Packages

- installed(): list all the installed packages
- install(Object zip): install a signed zip, you can pass in the bytes or the url to fetch the bytes. note that two interactions are possible:
	- if the author is not trusted yet, you will be prompted to trust him (defaults to yes in unsupervised mode)
	- if a different version of the same package is already installed, you will be prompted to ask whether you want to update it (defaults to yes in unsupervised)
- sign(Object zipContent, String module, String version, String profile, String profilePassword): you can pass in an unsigned zip (either bytes or url), indicate which module and version this is about and choose a profile to sign it with
- uninstall(packagedescription): you can pass in one or more package descriptions as returned by both installed() and authored()

## Environment

Triton will add an environment method before the "standard" one. If you used namespaced access you will still get the standard one, if you use unnamespaced access, the environment method is switched to the triton one.

The environment method in triton is compatible with the existing one but it has a few additional features:

```
# get all environment data
content = environment()
# update a setting
content/setting = "test"
# set all environment data
environment(content)

# get the current value for that setting without a default
# if no value exists, the user will be prompted for one! in unsupervised mode this returns the default value (if any)
environment("setting")

# force reinputting the environment variable, even if it already has a value
# prompt it so it is masked like a password
environment("setting", force: true, secret: true)
```

### Server Name

Triton will, by default, use the local name of the host it is on.
You can however give it a more meaningful name by passing in the ``name`` property.

Alternatively, once triton is started up, you can use the command ``name()`` to both fetch the current (without a parameter) and set a new one (if you pass in a parameter).
Note that if you are using self signed certificates and you update the name of a server, you will be prompted to regenerate the certificate to match the new name (defaults to yes).

Note that any user connecting to the server after the regenerated certificate is set, will be prompted to accept the new one. This may lead to confusion so this should preferably be done soon after installation of the triton server.

Note that for enterprise environments the certificates should be managed centrally. There will be no prompt to regenerate in such a case.

## System fallback

Because triton is used primarily for system manage, any method that is not namespaced and not recognized, is automatically assumed to be a system command.
For example if we just type ``ls()`` on a linux system, we will list the files in the current directory.
This is exactly the same as explicitly typing ``system.ls()``


# Force deinstallation on ubuntu

```
# remove the descriptor files used to run deinstallation scripts etc (they don't work cause they expect a GUI for now)
sudo rm /var/lib/dpkg/info/triton-server.*
# hard remove the package
sudo dpkg --remove --force-remove-reinstreq triton-server
# remove the files
sudo rm -rf /opt/triton-server/
```





# Misc

## Name

Triton is the messenger of the sea god Poseidon.
Interestingly, it can be multiplied into "tritones".

# Goal

Triton is a standalone component that can be run on a machine. It will connect to a central instance (referred to as Poseidon) and get instructions to run on the local machine. The connection uses websockets for full duplex communication over a persistent connection while reusing the firewall friendly http protocol.

Triton will have three main responsibilities:

- System health
	- triton will use a heartbeat to send system information (disk space usage, cpu usage, memory usage...)
	- the persistance of the connection and the heartbeat combine to make it easy to figure out if a system is down, poseidon can use this
- Remote management
	- triton allows management of arbitrary glue files to be pushed from poseidon
	- this allows you to do all sorts of management automation like
		- installation of software
		- software updates
		- ssh key management
		- ssh tunneling
		- restarting of services
		...
- CEF Events: triton will allow pushing 

# Offline

Ideally triton can still be used without poseidon, through a local interface. This would allow it to retain some of its features in environments where the central hookup is not allowed.
We could set up a local socket (can only be connected to from localhost, ssh inbound is seen as localhost) that allows you to send commands?
The local socket requirement prevents the need for identification.

# Identifier

Each triton client connects with an identifier to the central instance.
This identifier can be provided or it can be generated. The identifier is generally a very long randomly generated value, comparable to an API key.
The central instance can use this identifier to assign attributes to this instance (e.g. a name, a script repository,...)

It is possible for multiple triton clients to connect with the same identifier, this is especially relevant when dealing with auto-scaling clusters.

# Clustering

Triton clients are _not_ cluster-aware but they can share an identifier to get identical settings.

# Security

It is advised to layer triton communication over https to encrypt traffic though http is also supported.
There is no additional security in the form of username and passwords, the identifier acts as an API key.

Note that poseidon can (and should) reject connections from API keys that are not registered centrally.
Triton will retry connecting from time to time in case of rejection, assuming the system administrator still has to get around to registering the API key.

# Format

Triton exclusively uses "triton messages" to communicate, each triton message has these fields:

- type: the type of message
- version: the version of the type (defaults to 1)
- payload: the content of the message
- id: the unique id for this message
- conversationId: the first message in a conversation will have a conversation id that is identical to the id, subsequent messages sent as part of the same conversation have the original conversation id

The messages are serialized as XML before they are sent to poseidon over the websocket connection.

Based on the type, poseidon can decide where it has to go. The payload usually contains structured XML or JSON data but it is optional and can be left out completely. In general we prefer JSON to minimize the need for encoding for XML-in-XML.
If a binary payload is required, it must be base64 encoded and stored in the payload as a string.

# Encoding

Triton uses UTF-8 encoding by default.

# Plugins

The triton core is mostly interested in maintaining the connection to the server and allow dissemination of commands while allowing system commands to be run. Everything else is distributed in plugins.

Planned plugins:

- glue runner: allow synchronization & running of glue scripts and commands
- health check: send information about the server health