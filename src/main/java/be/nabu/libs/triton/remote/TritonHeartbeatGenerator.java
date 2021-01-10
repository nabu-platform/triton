package be.nabu.libs.triton.remote;

import java.util.ArrayList;
import java.util.List;

import be.nabu.libs.triton.remote.TritonHeartbeat.TritonFileSystem;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.CentralProcessor.TickType;
import oshi.hardware.CentralProcessor.ProcessorIdentifier;
import oshi.hardware.GlobalMemory;
import oshi.hardware.VirtualMemory;
import oshi.software.os.FileSystem;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;
import oshi.software.os.OperatingSystem.OSVersionInfo;

public class TritonHeartbeatGenerator {
	
	private SystemInfo system;
	private GlobalMemory memory;
	private CentralProcessor cpu;
	private ProcessorIdentifier processorIdentifier;
	private FileSystem fileSystem;
	private long[] oldTicks;
	private long[][] oldCpuTicks;
	private OperatingSystem operatingSystem;
	
	public TritonHeartbeatGenerator() {
		system = new SystemInfo();
		memory = system.getHardware().getMemory();
		cpu = system.getHardware().getProcessor();
		processorIdentifier = cpu.getProcessorIdentifier();
		operatingSystem = system.getOperatingSystem();
		fileSystem = operatingSystem.getFileSystem();
		oldTicks = new long[TickType.values().length];
		oldCpuTicks = new long[cpu.getLogicalProcessorCount()][TickType.values().length];
	}
	
	public TritonHeartbeat generate() {
		TritonHeartbeat heartbeat = new TritonHeartbeat();
		
		// general
		heartbeat.setPlatform(SystemInfo.getCurrentPlatformEnum().toString());
		heartbeat.setUptime(operatingSystem.getSystemUptime());
		heartbeat.setOs(operatingSystem.toString());
		heartbeat.setOsManufacturer(operatingSystem.getManufacturer());
		OSVersionInfo versionInfo = operatingSystem.getVersionInfo();
		heartbeat.setOsVersion(versionInfo.getVersion());
		heartbeat.setOsBuild(versionInfo.getBuildNumber());
		heartbeat.setOsCodeName(versionInfo.getCodeName());
		
		// memory
		heartbeat.setPhysicalMemoryTotal(memory.getTotal());
		heartbeat.setPhysicalMemoryAvailable(memory.getAvailable());
		heartbeat.setPhysicalMemoryUsed(memory.getTotal() - memory.getAvailable());
		VirtualMemory virtualMemory = memory.getVirtualMemory();
		heartbeat.setVirtualMemoryTotal(virtualMemory.getSwapTotal());
		heartbeat.setVirtualMemoryAvailable(virtualMemory.getSwapTotal() - virtualMemory.getSwapUsed());
		heartbeat.setVirtualMemoryUsed(virtualMemory.getSwapUsed());
		
		// cpu
		heartbeat.setPhysicalProcessorPackageCount(cpu.getPhysicalPackageCount());
		heartbeat.setPhysicalProcessorCount(cpu.getPhysicalProcessorCount());
		heartbeat.setLogicalProcessorCount(cpu.getLogicalProcessorCount());
		heartbeat.setProcessorName(processorIdentifier.getName());
		heartbeat.setProcessorVendor(processorIdentifier.getVendor());
		heartbeat.setSystemLoad(cpu.getSystemCpuLoadBetweenTicks(oldTicks));
		heartbeat.setProcessorLoad(cpu.getProcessorCpuLoadBetweenTicks(oldCpuTicks));
		oldTicks = cpu.getSystemCpuLoadTicks();
		oldCpuTicks = cpu.getProcessorCpuLoadTicks();
		
		// filesystem (open vs max file descriptors, file system % full etc)
		heartbeat.setFileDescriptorAvailable(fileSystem.getMaxFileDescriptors() - fileSystem.getOpenFileDescriptors());
		heartbeat.setFileDescriptorTotal(fileSystem.getMaxFileDescriptors());
		heartbeat.setFileDescriptorUsed(fileSystem.getOpenFileDescriptors());
		
		List<TritonFileSystem> fileSystems = new ArrayList<TritonFileSystem>();
		for (OSFileStore fileStore : fileSystem.getFileStores()) {
			// at least on linux, file stores with no uuid are things like snaps, sshfs,... generally not mounted through fstab and less relevant to keep an eye on
			// the problem is that they can be very numerous and hard to distinguish and (in the case of snap packages) 100% full as they are sized to the exact requirements
			// to prevent spamming poseidon with metrics and once that would seem to hint at full filesystems at that, we are ignoring these
			// in the future we could alternatively assume any file system worth watching must be at least a few gb big and filter on size, few snap packags would grow to such sizes
			// alternatively we could just filter out /snap mount points, but there are other systems like it...
			if (fileStore.getUUID() != null) {
				TritonFileSystem fileSystem = new TritonFileSystem();
				fileSystem.setId(fileStore.getUUID());
				fileSystem.setMount(fileStore.getMount());
				fileSystem.setName(fileStore.getName());
				// not sure what difference is with getFreeSpace?
				fileSystem.setSpaceAvailable(fileStore.getUsableSpace());
				fileSystem.setSpaceTotal(fileStore.getTotalSpace());
				fileSystem.setSpaceUsed(fileStore.getTotalSpace() - fileStore.getUsableSpace());
				fileSystem.setType(fileStore.getType());
				fileSystem.setVolume(fileStore.getVolume());
				fileSystems.add(fileSystem);
			}
		}
		heartbeat.setFileSystems(fileSystems);
		
		return heartbeat;
	}
}
