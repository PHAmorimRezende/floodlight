/**
 *    Copyright 2011, Big Switch Networks, Inc.
 *    Originally created by David Erickson, Stanford University
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 **/

package net.floodlightcontroller.forwarding;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.PortChangeType;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.types.NodePortTuple;
import net.floodlightcontroller.core.util.AppCookie;
import net.floodlightcontroller.debugcounter.IDebugCounterService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryListener;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.IPv6;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.routing.ForwardingBase;
import net.floodlightcontroller.routing.IRoutingDecision;
import net.floodlightcontroller.routing.IRoutingService;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.util.FlowModUtils;
import net.floodlightcontroller.util.OFDPAUtils;
import net.floodlightcontroller.util.OFMessageUtils;
import net.floodlightcontroller.util.OFPortMode;
import net.floodlightcontroller.util.OFPortModeTuple;
import net.floodlightcontroller.util.ParseUtils;

import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFFlowModCommand;
import org.projectfloodlight.openflow.protocol.OFGroupType;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.protocol.OFVersion;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IPv6Address;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFGroup;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.OFVlanVidMatch;
import org.projectfloodlight.openflow.types.TableId;
import org.projectfloodlight.openflow.types.U64;
import org.projectfloodlight.openflow.types.VlanVid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Forwarding extends ForwardingBase implements IFloodlightModule, IOFSwitchListener, ILinkDiscoveryListener {
    protected static Logger log = LoggerFactory.getLogger(Forwarding.class);

    @Override
    public Command processPacketInMessage(IOFSwitch sw, OFPacketIn pi, IRoutingDecision decision, FloodlightContext cntx) {
        Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
        // We found a routing decision (i.e. Firewall is enabled... it's the only thing that makes RoutingDecisions)
        if (decision != null) {
            if (log.isTraceEnabled()) {
                log.trace("Forwarding decision={} was made for PacketIn={}", decision.getRoutingAction().toString(), pi);
            }

            switch(decision.getRoutingAction()) {
            case NONE:
                // don't do anything
                return Command.CONTINUE;
            case FORWARD_OR_FLOOD:
            case FORWARD:
                doForwardFlow(sw, pi, cntx, false);
                return Command.CONTINUE;
            case MULTICAST:
                // treat as broadcast
                doFlood(sw, pi, cntx);
                return Command.CONTINUE;
            case DROP:
                doDropFlow(sw, pi, decision, cntx);
                return Command.CONTINUE;
            default:
                log.error("Unexpected decision made for this packet-in={}", pi, decision.getRoutingAction());
                return Command.CONTINUE;
            }
        } else { // No routing decision was found. Forward to destination or flood if bcast or mcast.
            if (log.isTraceEnabled()) {
                log.trace("No decision was made for PacketIn={}, forwarding", pi);
            }

            if (eth.isBroadcast() || eth.isMulticast()) {
                doFlood(sw, pi, cntx);
            } else {
                doForwardFlow(sw, pi, cntx, false);
            }
        }

        return Command.CONTINUE;
    }

    protected void doDropFlow(IOFSwitch sw, OFPacketIn pi, IRoutingDecision decision, FloodlightContext cntx) {
        OFPort inPort = (pi.getVersion().compareTo(OFVersion.OF_12) < 0 ? pi.getInPort() : pi.getMatch().get(MatchField.IN_PORT));
        Match m = createMatchFromPacket(sw, inPort, cntx);
        OFFlowMod.Builder fmb = sw.getOFFactory().buildFlowAdd(); // this will be a drop-flow; a flow that will not output to any ports
        List<OFAction> actions = new ArrayList<OFAction>(); // set no action to drop
        U64 cookie = AppCookie.makeCookie(FORWARDING_APP_ID, 0);
        log.info("Dropping");
        fmb.setCookie(cookie)
        .setHardTimeout(FLOWMOD_DEFAULT_HARD_TIMEOUT)
        .setIdleTimeout(FLOWMOD_DEFAULT_IDLE_TIMEOUT)
        .setBufferId(OFBufferId.NO_BUFFER) 
        .setMatch(m)
        .setPriority(FLOWMOD_DEFAULT_PRIORITY);

        FlowModUtils.setActions(fmb, actions, sw);

        /* Configure for particular switch pipeline */
        if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_10) != 0) {
            fmb.setTableId(FLOWMOD_DEFAULT_TABLE_ID);
        }

        if (log.isDebugEnabled()) {
            log.debug("write drop flow-mod sw={} match={} flow-mod={}",
                    new Object[] { sw, m, fmb.build() });
        }
        boolean dampened = messageDamper.write(sw, fmb.build());
        log.debug("OFMessage dampened: {}", dampened);
    }

    protected void doForwardFlow(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx, boolean requestFlowRemovedNotifn) {
        OFPort inPort = (pi.getVersion().compareTo(OFVersion.OF_12) < 0 ? pi.getInPort() : pi.getMatch().get(MatchField.IN_PORT));
        IDevice dstDevice = IDeviceService.fcStore.get(cntx, IDeviceService.CONTEXT_DST_DEVICE);
        DatapathId source = sw.getId();

        if (dstDevice != null) {
            IDevice srcDevice = IDeviceService.fcStore.get(cntx, IDeviceService.CONTEXT_SRC_DEVICE);

            if (srcDevice == null) {
                log.error("No device entry found for source device. Is the device manager running? If so, report bug.");
                return;
            }

            if (FLOOD_ALL_ARP_PACKETS && 
                    IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD).getEtherType() 
                    == EthType.ARP) {
                log.debug("ARP flows disabled in Forwarding. Flooding ARP packet");
                doFlood(sw, pi, cntx);
                return;
            }

            /* Validate that the source and destination are not on the same switch port */
            boolean on_same_if = false;
            for (SwitchPort dstDap : dstDevice.getAttachmentPoints()) {
                if (sw.getId().equals(dstDap.getNodeId()) && inPort.equals(dstDap.getPortId())) {
                    on_same_if = true;
                }
                break;
            }

            if (on_same_if) {
                log.info("Both source and destination are on the same switch/port {}/{}. Action = NOP", sw.toString(), inPort);
                return;
            }

            SwitchPort[] dstDaps = dstDevice.getAttachmentPoints();
            SwitchPort dstDap = null;

            /* 
             * Search for the true attachment point. The true AP is
             * not an endpoint of a link. It is a switch port w/o an
             * associated link. Note this does not necessarily hold
             * true for devices that 'live' between OpenFlow islands.
             * 
             * TODO Account for the case where a device is actually
             * attached between islands (possibly on a non-OF switch
             * in between two OpenFlow switches).
             */
            for (SwitchPort ap : dstDaps) {
                if (topologyService.isEdge(ap.getNodeId(), ap.getPortId())) {
                    dstDap = ap;
                    break;
                }
            }	

            /* 
             * This should only happen (perhaps) when the controller is
             * actively learning a new topology and hasn't discovered
             * all links yet, or a switch was in standalone mode and the
             * packet in question was captured in flight on the dst point
             * of a link.
             */
            if (dstDap == null) {
                log.warn("Could not locate edge attachment point for device {}. Flooding packet");
                doFlood(sw, pi, cntx);
                return; 
            }

            /* It's possible that we learned packed destination while it was in flight */
            if (!topologyService.isEdge(source, inPort)) {	
                log.debug("Packet destination is known, but packet was not received on an edge port (rx on {}/{}). Flooding packet", source, inPort);
                doFlood(sw, pi, cntx);
                return; 
            }				

            Route route = routingEngineService.getRoute(source, 
                    inPort,
                    dstDap.getNodeId(),
                    dstDap.getPortId(), U64.of(0)); //cookie = 0, i.e., default route

            Match m = createMatchFromPacket(sw, inPort, cntx);
            U64 cookie = AppCookie.makeCookie(FORWARDING_APP_ID, 0);

            if (route != null) {
                if (log.isDebugEnabled()) {
                    log.debug("pushRoute inPort={} route={} " +
                            "destination={}:{}",
                            new Object[] { inPort, route,
                                    dstDap.getNodeId(),
                                    dstDap.getPortId()});
                }


                log.debug("Cretaing flow rules on the route, match rule: {}", m);
                pushRoute(route, m, pi, sw.getId(), cookie, 
                        cntx, requestFlowRemovedNotifn,
                        OFFlowModCommand.ADD);	
            } else {
                /* Route traverses no links --> src/dst devices on same switch */
                log.debug("Could not compute route. Devices should be on same switch src={} and dst={}", srcDevice, dstDevice);
                Route r = new Route(srcDevice.getAttachmentPoints()[0].getNodeId(), dstDevice.getAttachmentPoints()[0].getNodeId());
                List<NodePortTuple> path = new ArrayList<NodePortTuple>(2);
                path.add(new NodePortTuple(srcDevice.getAttachmentPoints()[0].getNodeId(),
                        srcDevice.getAttachmentPoints()[0].getPortId()));
                path.add(new NodePortTuple(dstDevice.getAttachmentPoints()[0].getNodeId(),
                        dstDevice.getAttachmentPoints()[0].getPortId()));
                r.setPath(path);
                pushRoute(r, m, pi, sw.getId(), cookie,
                        cntx, requestFlowRemovedNotifn,
                        OFFlowModCommand.ADD);
            }
        } else {
            log.debug("Destination unknown. Flooding packet");
            doFlood(sw, pi, cntx);
        }
    }

    /**
     * Instead of using the Firewall's routing decision Match, which might be as general
     * as "in_port" and inadvertently Match packets erroneously, construct a more
     * specific Match based on the deserialized OFPacketIn's payload, which has been 
     * placed in the FloodlightContext already by the Controller.
     * 
     * @param sw, the switch on which the packet was received
     * @param inPort, the ingress switch port on which the packet was received
     * @param cntx, the current context which contains the deserialized packet
     * @return a composed Match object based on the provided information
     */
    protected Match createMatchFromPacket(IOFSwitch sw, OFPort inPort, FloodlightContext cntx) {
        // The packet in match will only contain the port number.
        // We need to add in specifics for the hosts we're routing between.
        Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
        VlanVid vlan = VlanVid.ofVlan(eth.getVlanID());
        MacAddress srcMac = eth.getSourceMACAddress();
        MacAddress dstMac = eth.getDestinationMACAddress();

        Match.Builder mb = sw.getOFFactory().buildMatch();
        mb.setExact(MatchField.IN_PORT, inPort);

        if (FLOWMOD_DEFAULT_MATCH_MAC) {
            mb.setExact(MatchField.ETH_SRC, srcMac)
            .setExact(MatchField.ETH_DST, dstMac);
        }

        if (FLOWMOD_DEFAULT_MATCH_VLAN) {
            if (!vlan.equals(VlanVid.ZERO)) {
                mb.setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlanVid(vlan));
            }
        }

        // TODO Detect switch type and match to create hardware-implemented flow
        if (eth.getEtherType() == EthType.IPv4) { /* shallow check for equality is okay for EthType */
            IPv4 ip = (IPv4) eth.getPayload();
            IPv4Address srcIp = ip.getSourceAddress();
            IPv4Address dstIp = ip.getDestinationAddress();

            if (FLOWMOD_DEFAULT_MATCH_IP_ADDR) {
                mb.setExact(MatchField.ETH_TYPE, EthType.IPv4)
                .setExact(MatchField.IPV4_SRC, srcIp)
                .setExact(MatchField.IPV4_DST, dstIp);
            }

            if (FLOWMOD_DEFAULT_MATCH_TRANSPORT) {
                /*
                 * Take care of the ethertype if not included earlier,
                 * since it's a prerequisite for transport ports.
                 */
                if (!FLOWMOD_DEFAULT_MATCH_IP_ADDR) {
                    mb.setExact(MatchField.ETH_TYPE, EthType.IPv4);
                }

                if (ip.getProtocol().equals(IpProtocol.TCP)) {
                    TCP tcp = (TCP) ip.getPayload();
                    mb.setExact(MatchField.IP_PROTO, IpProtocol.TCP)
                    .setExact(MatchField.TCP_SRC, tcp.getSourcePort())
                    .setExact(MatchField.TCP_DST, tcp.getDestinationPort());
                } else if (ip.getProtocol().equals(IpProtocol.UDP)) {
                    UDP udp = (UDP) ip.getPayload();
                    mb.setExact(MatchField.IP_PROTO, IpProtocol.UDP)
                    .setExact(MatchField.UDP_SRC, udp.getSourcePort())
                    .setExact(MatchField.UDP_DST, udp.getDestinationPort());
                }
            }
        } else if (eth.getEtherType() == EthType.ARP) { /* shallow check for equality is okay for EthType */
            mb.setExact(MatchField.ETH_TYPE, EthType.ARP);
        } else if (eth.getEtherType() == EthType.IPv6) {
            IPv6 ip = (IPv6) eth.getPayload();
            IPv6Address srcIp = ip.getSourceAddress();
            IPv6Address dstIp = ip.getDestinationAddress();

            if (FLOWMOD_DEFAULT_MATCH_IP_ADDR) {
                mb.setExact(MatchField.ETH_TYPE, EthType.IPv6)
                .setExact(MatchField.IPV6_SRC, srcIp)
                .setExact(MatchField.IPV6_DST, dstIp);
            }

            if (FLOWMOD_DEFAULT_MATCH_TRANSPORT) {
                /*
                 * Take care of the ethertype if not included earlier,
                 * since it's a prerequisite for transport ports.
                 */
                if (!FLOWMOD_DEFAULT_MATCH_IP_ADDR) {
                    mb.setExact(MatchField.ETH_TYPE, EthType.IPv6);
                }

                if (ip.getNextHeader().equals(IpProtocol.TCP)) {
                    TCP tcp = (TCP) ip.getPayload();
                    mb.setExact(MatchField.IP_PROTO, IpProtocol.TCP)
                    .setExact(MatchField.TCP_SRC, tcp.getSourcePort())
                    .setExact(MatchField.TCP_DST, tcp.getDestinationPort());
                } else if (ip.getNextHeader().equals(IpProtocol.UDP)) {
                    UDP udp = (UDP) ip.getPayload();
                    mb.setExact(MatchField.IP_PROTO, IpProtocol.UDP)
                    .setExact(MatchField.UDP_SRC, udp.getSourcePort())
                    .setExact(MatchField.UDP_DST, udp.getDestinationPort());
                }
            }
        }
        return mb.build();
    }

    /**
     * Creates a OFPacketOut with the OFPacketIn data that is flooded on all ports unless
     * the port is blocked, in which case the packet will be dropped.
     * @param sw The switch that receives the OFPacketIn
     * @param pi The OFPacketIn that came to the switch
     * @param cntx The FloodlightContext associated with this OFPacketIn
     */
    protected void doFlood(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {
        OFPort inPort = (pi.getVersion().compareTo(OFVersion.OF_12) < 0 ? pi.getInPort() : pi.getMatch().get(MatchField.IN_PORT));
        // Set Action to flood
        OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
        List<OFAction> actions = new ArrayList<OFAction>();
        Set<OFPort> broadcastPorts = this.topologyService.getSwitchBroadcastPorts(sw.getId());

        if (broadcastPorts.isEmpty()) {
            log.warn("No broadcast ports found. Using FLOOD output action");
            /* Must be a single-switch w/no links */
            broadcastPorts = Collections.singleton(OFPort.FLOOD);
        }

        for (OFPort p : broadcastPorts) {
            if (p.equals(inPort)) continue;
            actions.add(sw.getOFFactory().actions().output(p, Integer.MAX_VALUE));
        }
        pob.setActions(actions);
        // log.info("actions {}",actions);
        // set buffer-id, in-port and packet-data based on packet-in
        pob.setBufferId(OFBufferId.NO_BUFFER);
        OFMessageUtils.setInPort(pob, inPort);
        pob.setData(pi.getData());

        if (log.isTraceEnabled()) {
            log.trace("Writing flood PacketOut switch={} packet-in={} packet-out={}",
                    new Object[] {sw, pi, pob.build()});
        }
        messageDamper.write(sw, pob.build());

        return;
    }

    // IFloodlightModule methods

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        // We don't export any services
        return null;
    }

    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService>
    getServiceImpls() {
        // We don't have any services
        return null;
    }

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
        Collection<Class<? extends IFloodlightService>> l =
                new ArrayList<Class<? extends IFloodlightService>>();
        l.add(IFloodlightProviderService.class);
        l.add(IDeviceService.class);
        l.add(IRoutingService.class);
        l.add(ITopologyService.class);
        l.add(IDebugCounterService.class);
        l.add(ILinkDiscoveryService.class);
        return l;
    }

    @Override
    public void init(FloodlightModuleContext context) throws FloodlightModuleException {
        super.init();
        this.floodlightProviderService = context.getServiceImpl(IFloodlightProviderService.class);
        this.deviceManagerService = context.getServiceImpl(IDeviceService.class);
        this.routingEngineService = context.getServiceImpl(IRoutingService.class);
        this.topologyService = context.getServiceImpl(ITopologyService.class);
        this.debugCounterService = context.getServiceImpl(IDebugCounterService.class);
        this.switchService = context.getServiceImpl(IOFSwitchService.class);
        this.linkService = context.getServiceImpl(ILinkDiscoveryService.class);

        Map<String, String> configParameters = context.getConfigParams(this);
        String tmp = configParameters.get("hard-timeout");
        if (tmp != null) {
            FLOWMOD_DEFAULT_HARD_TIMEOUT = ParseUtils.parseHexOrDecInt(tmp);
            log.info("Default hard timeout set to {}.", FLOWMOD_DEFAULT_HARD_TIMEOUT);
        } else {
            log.info("Default hard timeout not configured. Using {}.", FLOWMOD_DEFAULT_HARD_TIMEOUT);
        }
        tmp = configParameters.get("idle-timeout");
        if (tmp != null) {
            FLOWMOD_DEFAULT_IDLE_TIMEOUT = ParseUtils.parseHexOrDecInt(tmp);
            log.info("Default idle timeout set to {}.", FLOWMOD_DEFAULT_IDLE_TIMEOUT);
        } else {
            log.info("Default idle timeout not configured. Using {}.", FLOWMOD_DEFAULT_IDLE_TIMEOUT);
        }
        tmp = configParameters.get("table-id");
        if (tmp != null) {
            FLOWMOD_DEFAULT_TABLE_ID = TableId.of(ParseUtils.parseHexOrDecInt(tmp));
            log.info("Default table ID set to {}.", FLOWMOD_DEFAULT_TABLE_ID);
        } else {
            log.info("Default table ID not configured. Using {}.", FLOWMOD_DEFAULT_TABLE_ID);
        }
        tmp = configParameters.get("priority");
        if (tmp != null) {
            FLOWMOD_DEFAULT_PRIORITY = ParseUtils.parseHexOrDecInt(tmp);
            log.info("Default priority set to {}.", FLOWMOD_DEFAULT_PRIORITY);
        } else {
            log.info("Default priority not configured. Using {}.", FLOWMOD_DEFAULT_PRIORITY);
        }
        tmp = configParameters.get("set-send-flow-rem-flag");
        if (tmp != null) {
            FLOWMOD_DEFAULT_SET_SEND_FLOW_REM_FLAG = Boolean.parseBoolean(tmp);
            log.info("Default flags will be set to SEND_FLOW_REM {}.", FLOWMOD_DEFAULT_SET_SEND_FLOW_REM_FLAG);
        } else {
            log.info("Default flags will be empty.");
        }
        tmp = configParameters.get("match");
        if (tmp != null) {
            tmp = tmp.toLowerCase();
            if (!tmp.contains("vlan") && !tmp.contains("mac") && !tmp.contains("ip") && !tmp.contains("port")) {
                /* leave the default configuration -- blank or invalid 'match' value */
            } else {
                FLOWMOD_DEFAULT_MATCH_VLAN = tmp.contains("vlan") ? true : false;
                FLOWMOD_DEFAULT_MATCH_MAC = tmp.contains("mac") ? true : false;
                FLOWMOD_DEFAULT_MATCH_IP_ADDR = tmp.contains("ip") ? true : false;
                FLOWMOD_DEFAULT_MATCH_TRANSPORT = tmp.contains("port") ? true : false;

            }
        }
        log.info("Default flow matches set to: VLAN=" + FLOWMOD_DEFAULT_MATCH_VLAN
                + ", MAC=" + FLOWMOD_DEFAULT_MATCH_MAC
                + ", IP=" + FLOWMOD_DEFAULT_MATCH_IP_ADDR
                + ", TPPT=" + FLOWMOD_DEFAULT_MATCH_TRANSPORT);

        tmp = configParameters.get("flood-arp");
        if (tmp != null) {
            tmp = tmp.toLowerCase();
            if (!tmp.contains("yes") && !tmp.contains("yep") && !tmp.contains("true") && !tmp.contains("ja") && !tmp.contains("stimmt")) {
                FLOOD_ALL_ARP_PACKETS = false;
                log.info("Not flooding ARP packets. ARP flows will be inserted for known destinations");
            } else {
                FLOOD_ALL_ARP_PACKETS = true;
                log.info("Flooding all ARP packets. No ARP flows will be inserted");
            }
        }

        tmp = configParameters.get("remove-flows-on-link-or-port-down");
        if (tmp != null) {
            REMOVE_FLOWS_ON_LINK_OR_PORT_DOWN = Boolean.parseBoolean(tmp);
        }
        if (REMOVE_FLOWS_ON_LINK_OR_PORT_DOWN) {
            log.info("Flows will be removed on link/port down events");
        } else {
            log.info("Flows will not be removed on link/port down events");
        }
    }

    @Override
    public void startUp(FloodlightModuleContext context) {
        super.startUp();
        switchService.addOFSwitchListener(this);

        /* Register only if we want to remove stale flows */
        if (REMOVE_FLOWS_ON_LINK_OR_PORT_DOWN) {
            linkService.addListener(this);
        }
    }

    @Override
    public void switchAdded(DatapathId switchId) {
    }

    @Override
    public void switchRemoved(DatapathId switchId) {		
    }

    @Override
    public void switchActivated(DatapathId switchId) {
        IOFSwitch sw = switchService.getSwitch(switchId);
        if (sw == null) {
            log.warn("Switch {} was activated but had no switch object in the switch service. Perhaps it quickly disconnected", switchId);
            return;
        }
        if (OFDPAUtils.isOFDPASwitch(sw)) {
            messageDamper.write(sw, sw.getOFFactory().buildFlowDelete()
                    .setTableId(TableId.ALL)
                    .build()
                    );
            messageDamper.write(sw, sw.getOFFactory().buildGroupDelete()
                    .setGroup(OFGroup.ANY)
                    .setGroupType(OFGroupType.ALL)
                    .build()
                    );
            messageDamper.write(sw, sw.getOFFactory().buildGroupDelete()
                    .setGroup(OFGroup.ANY)
                    .setGroupType(OFGroupType.INDIRECT)
                    .build()
                    );
            messageDamper.write(sw, sw.getOFFactory().buildBarrierRequest().build());

            List<OFPortModeTuple> portModes = new ArrayList<OFPortModeTuple>();
            for (OFPortDesc p : sw.getPorts()) {
                portModes.add(OFPortModeTuple.of(p.getPortNo(), OFPortMode.ACCESS));
            }
            if (log.isWarnEnabled()) {
                log.warn("For OF-DPA switch {}, initializing VLAN {} on ports {}", new Object[] { switchId, VlanVid.ZERO, portModes});
            }
            OFDPAUtils.addLearningSwitchPrereqs(sw, VlanVid.ZERO, portModes);
        }
    }

    @Override
    public void switchPortChanged(DatapathId switchId, OFPortDesc port, PortChangeType type) {	
        /* Port down events handled via linkDiscoveryUpdate(), which passes thru all events */
    }

    @Override
    public void switchChanged(DatapathId switchId) {
    }

    @Override
    public void switchDeactivated(DatapathId switchId) {
    }

    @Override
    public void linkDiscoveryUpdate(List<LDUpdate> updateList) {
        for (LDUpdate u : updateList) {
            /* Remove flows on either side if link/port went down */
            if (u.getOperation() == UpdateOperation.LINK_REMOVED ||
                    u.getOperation() == UpdateOperation.PORT_DOWN ||
                    u.getOperation() == UpdateOperation.TUNNEL_PORT_REMOVED) {
                Set<OFMessage> msgs = new HashSet<OFMessage>();

                if (u.getSrc() != null && !u.getSrc().equals(DatapathId.NONE)) {
                    IOFSwitch srcSw = switchService.getSwitch(u.getSrc());
                    /* src side of link */
                    if (srcSw != null) {
                        /* flows matching on src port */
                        msgs.add(srcSw.getOFFactory().buildFlowDelete()
                                .setCookie(AppCookie.makeCookie(FORWARDING_APP_ID, 0))
                                .setMatch(srcSw.getOFFactory().buildMatch()
                                        .setExact(MatchField.IN_PORT, u.getSrcPort())
                                        .build())
                                .build());
                        /* flows outputting to src port */
                        msgs.add(srcSw.getOFFactory().buildFlowDelete()
                                .setCookie(AppCookie.makeCookie(FORWARDING_APP_ID, 0))
                                .setOutPort(u.getSrcPort())
                                .build());
                        messageDamper.write(srcSw, msgs);
                        log.warn("{}. Removing flows to/from DPID={}, port={}", new Object[] { u.getType(), u.getSrc(), u.getSrcPort() });
                    }
                }

                /* must be a link, not just a port down, if we have a dst switch */
                if (u.getDst() != null && !u.getDst().equals(DatapathId.NONE)) {
                    /* dst side of link */
                    IOFSwitch dstSw = switchService.getSwitch(u.getDst());
                    if (dstSw != null) {
                        /* flows matching on dst port */
                        msgs.clear();
                        msgs.add(dstSw.getOFFactory().buildFlowDelete()
                                .setCookie(AppCookie.makeCookie(FORWARDING_APP_ID, 0))
                                .setMatch(dstSw.getOFFactory().buildMatch()
                                        .setExact(MatchField.IN_PORT, u.getDstPort())
                                        .build())
                                .build());
                        /* flows outputting to dst port */
                        msgs.add(dstSw.getOFFactory().buildFlowDelete()
                                .setCookie(AppCookie.makeCookie(FORWARDING_APP_ID, 0))
                                .setOutPort(u.getDstPort())
                                .build());
                        messageDamper.write(dstSw, msgs);
                        log.warn("{}. Removing flows to/from DPID={}, port={}", new Object[] { u.getType(), u.getDst(), u.getDstPort() });
                    }
                }
            }
        }
    }
}