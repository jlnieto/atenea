#!/usr/bin/env bash

# Versioned, non-secret dependency lock for Ubuntu 24.04 amd64 workers.

readonly ATENEA_TOOLCHAIN_LOCK_VERSION="1"
readonly ATENEA_TOOLCHAIN_OS_ID="ubuntu"
readonly ATENEA_TOOLCHAIN_OS_CODENAME="noble"
readonly ATENEA_TOOLCHAIN_ARCH="amd64"

readonly -a ATENEA_HOST_PACKAGE_PINS=(
  "acl=2.3.2-1build1.1"
  "bash=5.2.21-2ubuntu4"
  "ca-certificates=20260601~24.04.1"
  "curl=8.5.0-2ubuntu10.11"
  "dbus-user-session=1.14.10-4ubuntu4.1"
  "fuse-overlayfs=1.13-1"
  "git=1:2.43.0-1ubuntu7.3"
  "jq=1.7.1-3ubuntu0.24.04.2"
  "ripgrep=14.1.0-1"
  "rsync=3.2.7-1ubuntu1.5"
  "slirp4netns=1.2.1-1build2"
  "tar=1.35+dfsg-3ubuntu0.4"
  "tmux=3.4-1ubuntu0.1"
  "uidmap=1:4.13+dfsg1-4ubuntu3.2"
  "xz-utils=5.6.1+really5.4.5-1ubuntu0.3"
)

readonly ATENEA_DOCKER_VERSION="5:29.6.2-1~ubuntu.24.04~noble"
readonly ATENEA_CONTAINERD_VERSION="2.2.6-1~ubuntu.24.04~noble"
readonly ATENEA_BUILDX_VERSION="0.35.0-1~ubuntu.24.04~noble"
readonly ATENEA_COMPOSE_VERSION="5.3.1-1~ubuntu.24.04~noble"

readonly ATENEA_NODE_IMAGE="node:22.16.0-bookworm-slim@sha256:048ed02c5fd52e86fda6fbd2f6a76cf0d4492fd6c6fee9e2c463ed5108da0e34"
readonly ATENEA_MAVEN_JAVA21_IMAGE="maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e"
readonly ATENEA_TOMCAT_JAVA8_IMAGE="tomcat:8.5.100-jre8-temurin-jammy@sha256:e3ca75a4b11560bfb30894c3fa5d066ff0105e2e8e1ad183711df97606321e51"
readonly ATENEA_PLAYWRIGHT_IMAGE="mcr.microsoft.com/playwright:v1.60.0-noble@sha256:9bd26ad900bb5e0f4dee75839e957a89ae89c2b7ab1e76050e559790e946b948"
readonly ATENEA_PLAYWRIGHT_CHROMIUM_PATH="/ms-playwright/chromium-1223/chrome-linux64/chrome"
readonly ATENEA_PLAYWRIGHT_CHROMIUM_VERSION="Google Chrome for Testing 148.0.7778.96"
readonly ATENEA_PLAYWRIGHT_MODULE_VERSION="1.60.0"
readonly ATENEA_PLAYWRIGHT_MODULE_RELATIVE_ROOT="toolchain/playwright-module-v1"
readonly ATENEA_PLAYWRIGHT_PACKAGE_SHA256="a45820094f8f3872e7c1f7ca5d63cad884e862b067a7eea25cf91184398d43b4"
readonly ATENEA_PLAYWRIGHT_PACKAGE_LOCK_SHA256="008e76e4fe46b133621e327d03ad2bf62097cc9d11b463fe2e2a2ce56e0b8c89"
readonly ATENEA_PLAYWRIGHT_MODULE_TREE_SHA256="1ca49077563d996a21591e41f5a71296747d81ed9f1936e4887924fcb574b2ee"

readonly -a ATENEA_TOOLCHAIN_IMAGES=(
  "${ATENEA_NODE_IMAGE}"
  "${ATENEA_MAVEN_JAVA21_IMAGE}"
  "${ATENEA_TOMCAT_JAVA8_IMAGE}"
  "${ATENEA_PLAYWRIGHT_IMAGE}"
)
