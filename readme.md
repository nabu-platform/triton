# Name

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