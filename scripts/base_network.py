from mininet.net import Mininet
from mininet.node import RemoteController, OVSKernelSwitch
from mininet.link import TCLink
from mininet.cli import CLI

# Crea un oggetto Mininet
net = Mininet(switch=OVSKernelSwitch, ipBase='10.0.0.0')

# Aggiungi i nodi host alla rete
h1 = net.addHost('h1', ip='10.0.0.1', mac='00:00:00:00:00:01', defaultRoute='h1-eth0')
h2 = net.addHost('h2', ip='10.0.0.2', mac='00:00:00:00:00:02', defaultRoute='h2-eth0')
h3 = net.addHost('h3', ip='10.0.0.3', mac='00:00:00:00:00:03', defaultRoute='h3-eth0')

# Aggiungi lo switch alla rete
s1 = net.addSwitch('s1', dpid='1')

# Collega i nodi host allo switch
net.addLink(h1, s1, intfName1='h1-eth0', intfName2='s1-eth0')
net.addLink(h2, s1, intfName1='h2-eth0', intfName2='s1-eth1')
net.addLink(h3, s1, intfName1='h3-eth0', intfName2='s1-eth2')

net.addController(name='c0', controller=RemoteController, ip='127.0.0.1', port=6653, protocols='OpenFlow13')

# Avvia la rete
net.start()

# Avvia il prompt Mininet
CLI(net)

# Ferma la rete
net.stop()
