Once you copy the deb file to the server, run:

```
sudo apt install ./triton-server_0.0.1-1_amd64.deb
```

Note that this may seem to fail:

```
xdg-desktop-menu: file '/opt/triton-server/lib/triton-server-Triton' does not exist
dpkg: error processing package triton-server (--configure):
 installed triton-server package post-installation script subprocess returned error exit status 2
...
Errors were encountered while processing:
 triton-server
E: Sub-process /usr/bin/dpkg returned an error code (1)
```

This does not seem to prevent it from working though.

Install it as a service by creating this descriptor file:

```
[Unit]
Description=Triton Server

[Service]
ExecStart="/opt/triton-server/bin/Triton Server"
KillMode=process
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

Install this by running:

```
sudo systemctl enable /home/ubuntu/services/triton-server.service
```

Update bashrc to include some commands:

```
alias triton-start="sudo systemctl start triton-server"
alias triton-stop="sudo systemctl stop triton-server"
alias triton-restart="sudo systemctl restart triton-server"
alias triton-tail="sudo journalctl -f -u triton-server"
alias triton-log="sudo journalctl -e -u triton-server"
```
