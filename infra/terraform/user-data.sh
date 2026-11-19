#!/bin/bash
set -euxo pipefail

# Start SSM Agent first
systemctl enable --now amazon-ssm-agent

# Install Docker
dnf install -y docker

# Start Docker
systemctl enable --now docker

# Install Docker Compose plugin
mkdir -p /usr/local/lib/docker/cli-plugins

curl -fsSL --retry 3 \
  https://github.com/docker/compose/releases/download/v5.6.1/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose

chmod 755 /usr/local/lib/docker/cli-plugins/docker-compose

# Give ec2-user Docker permissions
usermod -aG docker ec2-user

# Create deployment directory
mkdir -p /home/ec2-user/react
chown ec2-user:ec2-user /home/ec2-user/react

# Verify installation
systemctl is-active amazon-ssm-agent
docker --version
docker compose version
