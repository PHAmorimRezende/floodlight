package net.floodlightcontroller.statistics;

import java.util.List;
import java.util.Map;

import org.projectfloodlight.openflow.protocol.OFStatsReply;
import org.projectfloodlight.openflow.protocol.OFStatsType;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.OFPort;

import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.types.NodePortTuple;

public interface IStatisticsService extends IFloodlightService {

	public SwitchPortBandwidth getBandwidthConsumption(DatapathId dpid, OFPort p);
		
	public Map<NodePortTuple, SwitchPortBandwidth> getBandwidthConsumption();
	
	public void collectStatistics(boolean collect);
	
	public List<OFStatsReply> getSwitchStatistics(DatapathId switchId, OFStatsType statsType);
}
