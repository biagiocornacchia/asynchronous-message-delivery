from mininet.net import Mininet
from mininet.node import RemoteController, OVSKernelSwitch
from mininet.cli import CLI

# create Mininet object
net = Mininet(switch=OVSKernelSwitch, ipBase='10.0.0.0/24')

# add hosts to the network (the first two are the clients, the others are the servers)
h1 = net.addHost('h1', ip='10.0.0.1', mac='00:00:00:00:00:01', defaultRoute='h1eth0')
h2 = net.addHost('h2', ip='10.0.0.2', mac='00:00:00:00:00:02', defaultRoute='h2eth0')
h3 = net.addHost('h3', ip='10.0.0.3', mac='00:00:00:00:00:03', defaultRoute='h3eth0')
h4 = net.addHost('h4', ip='10.0.0.4', mac='00:00:00:00:00:04', defaultRoute='h4eth0')
h5 = net.addHost('h5', ip='10.0.0.5', mac='00:00:00:00:00:05', defaultRoute='h5eth0')
h6 = net.addHost('h6', ip='10.0.0.6', mac='00:00:00:00:00:06', defaultRoute='h6eth0')
h7 = net.addHost('h7', ip='10.0.0.7', mac='00:00:00:00:00:07', defaultRoute='h7eth0')
h8 = net.addHost('h8', ip='10.0.0.8', mac='00:00:00:00:00:08', defaultRoute='h8eth0')

# add the switches
s1 = net.addSwitch('s1', dpid='1', mac='00:00:00:00:01:01')
s2 = net.addSwitch('s2', dpid='2', mac='00:00:00:00:01:02')
s3 = net.addSwitch('s3', dpid='3', mac='00:00:00:00:01:03')
s4 = net.addSwitch('s4', dpid='4', mac='00:00:00:00:01:04')

# link the hosts to the switches
net.addLink(h1, s1, intfName1='h1eth0', intfName2='s1eth0')
net.addLink(h2, s1, intfName1='h2eth0', intfName2='s1eth1')
net.addLink(h3, s2, intfName1='h3eth0', intfName2='s2eth0')
net.addLink(h4, s2, intfName1='h4eth0', intfName2='s2eth1')
net.addLink(h5, s3, intfName1='h5eth0', intfName2='s3eth0')
net.addLink(h6, s3, intfName1='h6eth0', intfName2='s3eth1')
net.addLink(h7, s4, intfName1='h7eth0', intfName2='s4eth0')
net.addLink(h8, s4, intfName1='h8eth0', intfName2='s4eth1')

# link the switches
net.addLink(s1, s2, intfName1='s1eth2', intfName2='s2eth2')
net.addLink(s1, s3, intfName1='s1eth3', intfName2='s3eth2')
net.addLink(s1, s4, intfName1='s1eth4', intfName2='s4eth2')

# add the remote controller
c0 = net.addController('c0', controller=RemoteController, ip='127.0.0.1', port=6653, protocols='OpenFlow13')

# start the network
net.start()

# start Mininet prompt
CLI(net)

# stop the network
net.stop()
