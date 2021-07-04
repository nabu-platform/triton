#!/bin/bash

path=`pwd`
user=`whoami`

# Make sure we have wget
echo "Ensure installation of wget"
sudo apt-get update && sudo apt-get install --assume-yes --no-install-recommends apt-utils wget

# Fetch the deb file
echo "Downloading the triton server package"
wget https://my.nabu.be/resources/triton-server-latest.deb

# Install it
echo "Installing the triton server package"
sudo apt-get install ./triton-server-latest.deb

# Create a service file for it
if [ ! -f "services/triton-server.service" ]; then
	echo "Installing triton as a systemd service"
	mkdir -p services
	printf "[Unit]\nDescription=Triton Server\n\n[Service]\nExecStart=/opt/triton-server/bin/triton-server\nKillMode=process\nRestart=on-failure\n\n[Install]\nWantedBy=multi-user.target\n" > services/triton-server.service

	# enable the system service
	sudo systemctl enable "$path/services/triton-server.service"
fi

# Create the aliases if necessary
if grep -q triton-restart ~/.bashrc; then
	echo "Aliases already registered"
else
	echo "Registering aliases"
	printf "\nalias triton-tail=\"sudo journalctl -f -u triton-server.service\"\nalias triton-log=\"sudo journalctl -e -u triton-server.service\"\nalias triton-start=\"sudo systemctl start triton-server\"\nalias triton-stop=\"sudo systemctl stop triton-server\"\nalias triton-restart=\"sudo systemctl restart triton-server\"\nalias triton-disable=\"sudo systemctl disable triton-server.service\"" >> ~/.bashrc \
		&& source ~/.bashrc
fi

echo "Starting triton server"
sudo systemctl start triton-server

echo "Don't forget to update the name of the server using the name() command"
