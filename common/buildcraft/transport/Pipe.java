/**
 * Copyright (c) SpaceToad, 2011
 * http://www.mod-buildcraft.com
 *
 * BuildCraft is distributed under the terms of the Minecraft Mod Public
 * License 1.0, or MMPL. Please check the contents of the license located in
 * http://www.mod-buildcraft.com/MMPL-1.0.txt
 */

package buildcraft.transport;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Random;

import buildcraft.BuildCraftTransport;
import buildcraft.api.core.BuildCraftAPI;
import buildcraft.api.core.Orientations;
import buildcraft.api.core.SafeTimeTracker;
import buildcraft.api.gates.Action;
import buildcraft.api.gates.ActionManager;
import buildcraft.api.gates.IAction;
import buildcraft.api.gates.IActionReceptor;
import buildcraft.api.gates.ITrigger;
import buildcraft.api.gates.ITriggerParameter;
import buildcraft.api.gates.Trigger;
import buildcraft.api.gates.TriggerParameter;
import buildcraft.api.transport.IPipe;
import buildcraft.core.ActionRedstoneOutput;
import buildcraft.core.CoreProxy;
import buildcraft.core.IDropControlInventory;
import buildcraft.core.Utils;
import buildcraft.core.network.IndexInPayload;
import buildcraft.core.network.PacketPayload;
import buildcraft.core.network.PacketUpdate;
import buildcraft.core.network.TileNetworkData;
import buildcraft.core.network.TilePacketWrapper;
import buildcraft.transport.Gate.GateConditional;

import net.minecraft.src.Entity;
import net.minecraft.src.EntityItem;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.ItemStack;
import net.minecraft.src.NBTTagCompound;
import net.minecraft.src.TileEntity;
import net.minecraft.src.World;

public abstract class Pipe implements IPipe, IDropControlInventory {

	public int[] signalStrength = new int[] { 0, 0, 0, 0 };

	public int xCoord;
	public int yCoord;
	public int zCoord;
	public World worldObj;
	public TileGenericPipe container;

	public final PipeTransport transport;
	public final PipeLogic logic;
	public final int itemID;

	private TilePacketWrapper networkPacket;

	private boolean internalUpdateScheduled = false;

	@TileNetworkData(intKind = TileNetworkData.UNSIGNED_BYTE)
	public boolean[] wireSet = new boolean[] { false, false, false, false };

	public Gate gate;

	@SuppressWarnings("rawtypes")
	private static Map<Class, TilePacketWrapper> networkWrappers = new HashMap<Class, TilePacketWrapper>();

	ITrigger[] activatedTriggers = new Trigger[8];
	ITriggerParameter[] triggerParameters = new ITriggerParameter[8];
	IAction[] activatedActions = new Action[8];

	@TileNetworkData(intKind = TileNetworkData.UNSIGNED_BYTE)
	public boolean broadcastSignal[] = new boolean[] { false, false, false, false };
	public boolean broadcastRedstone = false;

	public SafeTimeTracker actionTracker = new SafeTimeTracker();

	public Pipe(PipeTransport transport, PipeLogic logic, int itemID) {
		this.transport = transport;
		this.logic = logic;
		this.itemID = itemID;

		if (!networkWrappers.containsKey(this.getClass()))
			networkWrappers
					.put(this.getClass(), new TilePacketWrapper(new Class[] { TileGenericPipe.class, this.transport.getClass(),
							this.logic.getClass() }));

		this.networkPacket = networkWrappers.get(this.getClass());

	}

	private void setPosition(int xCoord, int yCoord, int zCoord) {
		this.xCoord = xCoord;
		this.yCoord = yCoord;
		this.zCoord = zCoord;

		transport.setPosition(xCoord, yCoord, zCoord);
		logic.setPosition(xCoord, yCoord, zCoord);
	}

	private void setWorld(World worldObj) {
		if (worldObj != null && this.worldObj == null) {
			this.worldObj = worldObj;
			transport.setWorld(worldObj);
			logic.setWorld(worldObj);
		}
	}

	public void setTile(TileEntity tile) {

		this.container = (TileGenericPipe) tile;

		transport.setTile((TileGenericPipe) tile);
		logic.setTile((TileGenericPipe) tile);

		setPosition(tile.xCoord, tile.yCoord, tile.zCoord);
		setWorld(tile.worldObj);
	}

	public boolean blockActivated(World world, int i, int j, int k, EntityPlayer entityplayer) {
		return logic.blockActivated(entityplayer);
	}

	public void onBlockPlaced() {
		logic.onBlockPlaced();
		transport.onBlockPlaced();
	}

	public void onNeighborBlockChange(int blockId) {
		logic.onNeighborBlockChange(blockId);
		transport.onNeighborBlockChange(blockId);

		updateSignalState();
	}

	public boolean isPipeConnected(TileEntity tile) {
		return logic.isPipeConnected(tile) && transport.isPipeConnected(tile);
	}

	/**
	 * Should return the texture file that is used to render this pipe
	 */
	public abstract String getTextureFile();

	/**
	 * Should return the textureindex in the file specified by getTextureFile() 
	 * @param direction The orientation for the texture that is requested. Unknown for the center pipe center 
	 * @return the index in the texture sheet
	 */
	public abstract int getTextureIndex(Orientations direction);
	
	
	/**
	 *  Should return the textureindex used by the Pipe Item Renderer, as this is done client-side the default implementation might 
	 *  not work if your getTextureIndex(Orienations.Unknown) has logic 
	 * @return
	 */
	public int getTextureIndexForItem(){
		return getTextureIndex(Orientations.Unknown);
	}
	
	public void updateEntity() {
		
		transport.updateEntity();
		logic.updateEntity();

		if (internalUpdateScheduled) {
			internalUpdate();
			internalUpdateScheduled = false;
		}

		// Do not try to update gates client side.
		if(CoreProxy.isRemote())
			return;
		
		if (actionTracker.markTimeIfDelay(worldObj, 10))
			resolveActions();

		// Update the gate if we have any
		if (gate != null)
			gate.update();

	}

	private void internalUpdate() {
		updateSignalState();
	}

	public void writeToNBT(NBTTagCompound nbttagcompound) {
		transport.writeToNBT(nbttagcompound);
		logic.writeToNBT(nbttagcompound);

		// Save pulser if any
		if (gate != null) {
			NBTTagCompound nbttagcompoundC = new NBTTagCompound();
			gate.writeToNBT(nbttagcompoundC);
			nbttagcompound.setTag("Gate", nbttagcompoundC);
		}

		for (int i = 0; i < 4; ++i)
			nbttagcompound.setBoolean("wireSet[" + i + "]", wireSet[i]);

		for (int i = 0; i < 8; ++i) {
			nbttagcompound.setInteger("action[" + i + "]", activatedActions[i] != null ? activatedActions[i].getId() : 0);
			nbttagcompound.setInteger("trigger[" + i + "]", activatedTriggers[i] != null ? activatedTriggers[i].getId() : 0);
		}

		for (int i = 0; i < 8; ++i)
			if (triggerParameters[i] != null) {
				NBTTagCompound cpt = new NBTTagCompound();
				triggerParameters[i].writeToNBT(cpt);
				nbttagcompound.setTag("triggerParameters[" + i + "]", cpt);
			}
	}

	public void readFromNBT(NBTTagCompound nbttagcompound) {
		transport.readFromNBT(nbttagcompound);
		logic.readFromNBT(nbttagcompound);

		// Load pulser if any
		if (nbttagcompound.hasKey("Gate")) {
			NBTTagCompound nbttagcompoundP = nbttagcompound.getCompoundTag("Gate");
			gate = new GateVanilla(this);
			gate.readFromNBT(nbttagcompoundP);
		} else if (nbttagcompound.hasKey("gateKind")) {
			// Legacy implementation
			Gate.GateKind kind = Gate.GateKind.values()[nbttagcompound.getInteger("gateKind")];
			if (kind != Gate.GateKind.None) {
				gate = new GateVanilla(this);
				gate.kind = kind;
			}
		}

		for (int i = 0; i < 4; ++i)
			wireSet[i] = nbttagcompound.getBoolean("wireSet[" + i + "]");

		for (int i = 0; i < 8; ++i) {
			activatedActions[i] = ActionManager.actions[nbttagcompound.getInteger("action[" + i + "]")];
			activatedTriggers[i] = ActionManager.triggers[nbttagcompound.getInteger("trigger[" + i + "]")];
		}

		for (int i = 0; i < 8; ++i)
			if (nbttagcompound.hasKey("triggerParameters[" + i + "]")) {
				triggerParameters[i] = new TriggerParameter();
				triggerParameters[i].readFromNBT(nbttagcompound.getCompoundTag("triggerParameters[" + i + "]"));
			}
	}

	private boolean initialized = false;

	public void initialize() {
		if (!initialized) {
			transport.initialize();
			logic.initialize();
			initialized = true;
			updateSignalState();
		}
	}

	private void readNearbyPipesSignal(WireColor color) {
		boolean foundBiggerSignal = false;

		for (Orientations o : Orientations.dirs()) {
			TileEntity tile = container.getTile(o);

			if (tile instanceof TileGenericPipe) {
				TileGenericPipe tilePipe = (TileGenericPipe) tile;

				if (BlockGenericPipe.isFullyDefined(tilePipe.pipe))
					if (isWireConnectedTo(tile, color))
						foundBiggerSignal |= receiveSignal(tilePipe.pipe.signalStrength[color.ordinal()] - 1, color);
			}
		}

		if (!foundBiggerSignal && signalStrength[color.ordinal()] != 0) {
			signalStrength[color.ordinal()] = 0;
			//worldObj.markBlockNeedsUpdate(xCoord, yCoord, zCoord);
			container.scheduleRenderUpdate();
			

			for (Orientations o : Orientations.dirs()) {
				TileEntity tile = container.getTile(o);

				if (tile instanceof TileGenericPipe) {
					TileGenericPipe tilePipe = (TileGenericPipe) tile;

					if (BlockGenericPipe.isFullyDefined(tilePipe.pipe))
						tilePipe.pipe.internalUpdateScheduled = true;
				}
			}
		}
	}

	private void updateSignalState() {
		for (IPipe.WireColor c : IPipe.WireColor.values())
			updateSignalStateForColor(c);
	}

	private void updateSignalStateForColor(IPipe.WireColor color) {
		if (!wireSet[color.ordinal()])
			return;

		// STEP 1: compute internal signal strength

		if (broadcastSignal[color.ordinal()])
			receiveSignal(255, color);
		else
			readNearbyPipesSignal(color);

		// STEP 2: transmit signal in nearby blocks

		if (signalStrength[color.ordinal()] > 1)
			for (Orientations o : Orientations.dirs()) {
				TileEntity tile = container.getTile(o);

				if (tile instanceof TileGenericPipe) {
					TileGenericPipe tilePipe = (TileGenericPipe) tile;

					if (BlockGenericPipe.isFullyDefined(tilePipe.pipe) && tilePipe.pipe.wireSet[color.ordinal()])
						if (isWireConnectedTo(tile, color))
							tilePipe.pipe.receiveSignal(signalStrength[color.ordinal()] - 1, color);
				}
			}
	}

	private boolean receiveSignal(int signal, IPipe.WireColor color) {
		if (worldObj == null)
			return false;

		int oldSignal = signalStrength[color.ordinal()];

		if (signal >= signalStrength[color.ordinal()] && signal != 0) {
			signalStrength[color.ordinal()] = signal;
			internalUpdateScheduled = true;

			if (oldSignal == 0) {
				//worldObj.markBlockNeedsUpdate(xCoord, yCoord, zCoord);
				container.scheduleRenderUpdate();
				
			}

			return true;
		} else
			return false;
	}

	public boolean inputOpen(Orientations from) {
		return transport.inputOpen(from) && logic.inputOpen(from);
	}

	public boolean outputOpen(Orientations to) {
		return transport.outputOpen(to) && logic.outputOpen(to);
	}

	public void onEntityCollidedWithBlock(Entity entity) {

	}

	public PacketPayload getNetworkPacket() {
		PacketPayload payload = networkPacket.toPayload(xCoord, yCoord, zCoord, new Object[] { container, transport, logic });

		return payload;
	}

	/**
	 * This is used by update packets and uses TileNetworkData. Should be
	 * unified with description packets!
	 * 
	 * @param packet
	 */
	public void handlePacket(PacketUpdate packet) {
		networkPacket.fromPayload(new Object[] { container, transport, logic }, packet.payload);
	}

	/**
	 * This is used by description packets.
	 * 
	 * @param payload
	 * @param index
	 */
	public void handleWirePayload(PacketPayload payload, IndexInPayload index) {
		for (int i = index.intIndex; i < index.intIndex + 4; i++)
			if (payload.intPayload[i] > 0)
				wireSet[i - index.intIndex] = true;
			else
				wireSet[i - index.intIndex] = false;
	}

	/**
	 * This is used by description packets.
	 * 
	 * @param payload
	 * @param index
	 */
	public void handleGatePayload(PacketPayload payload, IndexInPayload index) {
		gate = new GateVanilla(this);
		gate.fromPayload(payload, index);
	}

	public boolean isPoweringTo(int l) {
		if (!broadcastRedstone)
			return false;

		Orientations o = Orientations.values()[l].reverse();
		TileEntity tile = container.getTile(o);

		if (tile instanceof TileGenericPipe && Utils.checkPipesConnections(this.container, tile))
			return false;

		return true;
	}

	public boolean isIndirectlyPoweringTo(int l) {
		return isPoweringTo(l);
	}

	public void randomDisplayTick(Random random) {}

	// / @Override TODO: should be in IPipe
	public boolean isWired() {
		for (WireColor color : WireColor.values())
			if (isWired(color))
				return true;

		return false;
	}

	@Override
	public boolean isWired(WireColor color) {
		return wireSet[color.ordinal()];
	}

	@Override
	public boolean hasInterface() {
		return hasGate();
	}

	public boolean hasGate() {
		return gate != null;
	}

	public void onBlockRemoval() {
		if (wireSet[IPipe.WireColor.Red.ordinal()])
			Utils.dropItems(worldObj, new ItemStack(BuildCraftTransport.redPipeWire), xCoord, yCoord, zCoord);

		if (wireSet[IPipe.WireColor.Blue.ordinal()])
			Utils.dropItems(worldObj, new ItemStack(BuildCraftTransport.bluePipeWire), xCoord, yCoord, zCoord);

		if (wireSet[IPipe.WireColor.Green.ordinal()])
			Utils.dropItems(worldObj, new ItemStack(BuildCraftTransport.greenPipeWire), xCoord, yCoord, zCoord);

		if (wireSet[IPipe.WireColor.Yellow.ordinal()])
			Utils.dropItems(worldObj, new ItemStack(BuildCraftTransport.yellowPipeWire), xCoord, yCoord, zCoord);

		if (hasGate())
			gate.dropGate(worldObj, xCoord, yCoord, zCoord);
		
		for (Orientations direction : Orientations.dirs()){
			if (container.hasFacade(direction)){
				container.dropFacade(direction);
			}
		}
	}

	public void setTrigger(int position, ITrigger trigger) {
		activatedTriggers[position] = trigger;
	}

	public ITrigger getTrigger(int position) {
		return activatedTriggers[position];
	}

	public void setTriggerParameter(int position, ITriggerParameter p) {
		triggerParameters[position] = p;
	}

	public ITriggerParameter getTriggerParameter(int position) {
		return triggerParameters[position];
	}

	public boolean isNearbyTriggerActive(ITrigger trigger, ITriggerParameter parameter) {
		if (trigger instanceof ITriggerPipe)
			return ((ITriggerPipe) trigger).isTriggerActive(this, parameter);
		else if (trigger != null)
			for (Orientations o : Orientations.dirs()) {
				TileEntity tile = container.getTile(o);

				if (tile != null && !(tile instanceof TileGenericPipe) && trigger.isTriggerActive(tile, parameter))
					return true;
			}

		return false;
	}

	public boolean isTriggerActive(ITrigger trigger) {
		return false;
	}

	public LinkedList<IAction> getActions() {
		LinkedList<IAction> result = new LinkedList<IAction>();

		if (hasGate())
			gate.addActions(result);

		return result;
	}

	public IAction getAction(int position) {
		return activatedActions[position];
	}

	public void setAction(int position, IAction action) {
		activatedActions[position] = action;
	}

	public void resetGate() {
		gate = null;
		activatedTriggers = new Trigger[activatedTriggers.length];
        triggerParameters = new ITriggerParameter[triggerParameters.length];
        activatedActions = new Action[activatedActions.length];
        broadcastSignal = new boolean[] { false, false, false, false };
        broadcastRedstone = false;
		//worldObj.markBlockNeedsUpdate(xCoord, yCoord, zCoord);
        container.scheduleRenderUpdate();
        //worldObj.notifyBlocksOfNeighborChange(xCoord, yCoord, zCoord, BuildCraftTransport.genericPipeBlock.blockID);
	}

	private void resolveActions() {
		if (!hasGate())
			return;

		boolean oldBroadcastRedstone = broadcastRedstone;
		boolean[] oldBroadcastSignal = broadcastSignal;

		broadcastRedstone = false;
		broadcastSignal = new boolean[] { false, false, false, false };

		// Tell the gate to prepare for resolving actions. (Disable pulser)
		gate.startResolution();

		HashMap<Integer, Boolean> actions = new HashMap<Integer, Boolean>();

		// Computes the actions depending on the triggers
		for (int it = 0; it < 8; ++it) {
			ITrigger trigger = activatedTriggers[it];
			IAction action = activatedActions[it];
			ITriggerParameter parameter = triggerParameters[it];

			if (trigger != null && action != null)
				if (!actions.containsKey(action.getId()))
					actions.put(action.getId(), isNearbyTriggerActive(trigger, parameter));
				else if (gate.getConditional() == GateConditional.AND)
					actions.put(action.getId(), actions.get(action.getId()) && isNearbyTriggerActive(trigger, parameter));
				else
					actions.put(action.getId(), actions.get(action.getId()) || isNearbyTriggerActive(trigger, parameter));
		}

		// Activate the actions
		for (Integer i : actions.keySet())
			if (actions.get(i)) {

				// Custom gate actions take precedence over defaults.
				if (gate.resolveAction(ActionManager.actions[i]))
					continue;

				if (ActionManager.actions[i] instanceof ActionRedstoneOutput)
					broadcastRedstone = true;
				else if (ActionManager.actions[i] instanceof ActionSignalOutput)
					broadcastSignal[((ActionSignalOutput) ActionManager.actions[i]).color.ordinal()] = true;
				else
					for (int a = 0; a < container.tileBuffer.length; ++a)
						if (container.tileBuffer[a].getTile() instanceof IActionReceptor) {
							IActionReceptor recept = (IActionReceptor) container.tileBuffer[a].getTile();
							recept.actionActivated(ActionManager.actions[i]);
						}
			}

		actionsActivated(actions);

		if (oldBroadcastRedstone != broadcastRedstone) {
			container.scheduleRenderUpdate();
			//worldObj.markBlockNeedsUpdate(xCoord, yCoord, zCoord);
			worldObj.notifyBlocksOfNeighborChange(xCoord, yCoord, zCoord, BuildCraftTransport.genericPipeBlock.blockID);
		}

		for (int i = 0; i < oldBroadcastSignal.length; ++i)
			if (oldBroadcastSignal[i] != broadcastSignal[i]) {
				//worldObj.markBlockNeedsUpdate(xCoord, yCoord, zCoord);
				container.scheduleRenderUpdate();
				updateSignalState();
				break;
			}
	}

	protected void actionsActivated(HashMap<Integer, Boolean> actions) {

	}

	@Override
	public TileGenericPipe getContainer() {
		return container;
	}

	@Override
	public boolean isWireConnectedTo(TileEntity tile, WireColor color) {
		if (!(tile instanceof TileGenericPipe))
			return false;

		TileGenericPipe tilePipe = (TileGenericPipe) tile;

		if (!BlockGenericPipe.isFullyDefined(tilePipe.pipe))
			return false;

		if (!tilePipe.pipe.wireSet[color.ordinal()])
			return false;

		return (tilePipe.pipe.transport instanceof PipeTransportStructure || transport instanceof PipeTransportStructure || Utils
				.checkPipesConnections(container, tile));
	}

	public void dropContents() {
		transport.dropContents();
	}

	public void onDropped(EntityItem item) {

	}

	/**
	 * If this pipe is open on one side, return it.
	 */
	public Orientations getOpenOrientation() {
		int Connections_num = 0;

		Orientations target_orientation = Orientations.Unknown;

		for (Orientations o : Orientations.dirs())
			if (Utils.checkPipesConnections(container.getTile(o), container)) {

				Connections_num++;

				if (Connections_num == 1)
					target_orientation = o;
			}

		if (Connections_num > 1 || Connections_num == 0)
			return Orientations.Unknown;

		return target_orientation.reverse();
	}

	@Override
	public boolean doDrop() {
		return logic.doDrop();
	}
	
	public boolean isGateActive(){
		for (boolean b : broadcastSignal){
			if (b) return true;
		}
		return broadcastRedstone;
	}


	/**
	 * Called when TileGenericPipe.invalidate() is called 
	 */
	public void invalidate() {}

	/**
	 * Called when TileGenericPipe.validate() is called
	 */
	public void validate() {}

	
	/**
	 * Called when TileGenericPipe.onChunkUnload is called
	 */
	public void onChunkUnload() {}
}
