# LimboTransfer

## Introduction

You can join my [Discord](https://discord.gg/5z4GuSnqmQ) for help with this plugin. LimboTransfer is a plugin for [Limbo](https://github.com/LOOHP/Limbo) that adds transfer packet functionality, allowing players to be transferred to different servers.

## Installation

Place the latest [`LimboTransfer.jar`](https://github.com/Kas-tle/LimboTransfer/releases/latest/download/LimboTransfer.jar) file in the `plugins` directory of your Limbo server. The plugin will automatically load on server startup.

## Commands

All commands require the executor to have the `limbotransfer.transfer` permission.

- `/transfer <player> <host> <port>`
  - `player`: the player to be transferred
  - `host`: the hostname of the destination server
  - `port`: the port number of the destination server
- `/transferall <host> <port> [<batchDelayTicks> <batchSize>]`
  - `host`: the hostname of the destination server
  - `port`: the port number of the destination server
  - `batchDelayTicks`: the number of ticks to wait between transferring each batch of players
  - `batchSize`: the number of players to transfer at one time

## Building

```sh
git clone https://github.com/Kas-tle/LimboTransfer && cd LimboTransfer
./gradlew build
```