# Asynchronous Message Delivery

## Overview

This project consists of developing an **SDN-based network** that implements an **Asynchronous Message Delivery** (AMD) System. An AMD is a communication model
based on the store-and-forward technique that allows a client to send messages to a server
that can be temporarily offline. This results in decoupling the client and the server.

So, in the system can be identified two different entities: the **client** which sends the
messages and the **server** that receives and consumes them. To this aim, the server needs
to subscribe itself to the AMD system that will assign it a **virtual IP** and **MAC**. These
addresses can be used by the client to contact it. During the lifetime of the server in the
system, it can change its status from *online* to *offline*. In this case the system has to
buffer the incoming messages and resend them to it as soon as it is back online. Instead,
if the server is *online*, all client requests are delivered synchronously.

The server uses the **REST APIs** exposed by the system to *subscribe*/*unsubscribe* and
to update its status. Moreover, in a real case multiple servers are subscribed so even the
client can use the **REST APIs** to retrieve information about them.

The AMD system has been simulated using `Mininet` that allows to create a realistic virtual network, running real kernel, switch and application code. The network is composed
by `Open vSwitch` (OVS) instances that are open-source implementation of a distributed
virtual multi-layer switches, supporting the `OpenFlow protocol` to communicate with
the SDN controller. The latter is provided by a Java framework called `Floodlight` that
is an implementation of an OpenFlow controller.

## Getting Started

Firstly, it's necessary to clone the official Floodlight repository and this repository:

```bash
git clone https://github.com/floodlight/floodlight.git floodlight
git clone https://github.com/biagiocornacchia/Asynchronous-Message-Delivery amd
```

Then, copy the `amd/src/forwarding` and `amd/src/unipi` folders into `floodlight/src/main/java/net/floodlightcontroller/`. Finally, remember to add `net.floodlightcontroller.unipi.amd.AsynchronousMessageDelivery` in the `floodlightdefault.properties` and `IFloodlightModule` files. 

## Demo

The module can be tested in a simulated environment like `Mininet`. In particular, the `amd/scripts` folder contains two python scripts that create two different virtual networks. These scripts require the [mininet](https://mininet.org/api/annotated.html) package for python. Before starting one of the scripts, the floodlight controller must already be running.

To use the REST APIs, a browser extension like **Boomerang SOAP and REST Client** can be used. The endpoints exposed by the module are:

```http
POST http://<CONTROLLER_IP>:8080/amd/server/subscription/json
DELETE http://<CONTROLLER_IP>:8080/amd/server/subscription/json
PUT http://<CONTROLLER_IP>:8080/amd/server/status/json
GET http://<CONTROLLER_IP>:8080/amd/server/info/json
```

More information on how to build a request can be found in the [project report](docs/AMD%20-%20Report.pdf).

## Authors

* Biagio Cornacchia, b.cornacchia@studenti.unipi.it
* Matteo Abaterusso, m.abaterusso@studenti.unipi.it