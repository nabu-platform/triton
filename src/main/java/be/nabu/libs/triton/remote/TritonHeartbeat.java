/*
* Copyright (C) 2021 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package be.nabu.libs.triton.remote;

import java.util.List;

public class TritonHeartbeat {
	
	private String platform;
	
	private String os, osManufacturer, osBuild, osVersion, osCodeName;
	private long uptime;
	
	private long physicalMemoryTotal, physicalMemoryAvailable, physicalMemoryUsed, virtualMemoryTotal, virtualMemoryAvailable, virtualMemoryUsed;
	
	private String processorName, processorVendor;
	private int physicalProcessorCount, logicalProcessorCount, physicalProcessorPackageCount;
	private double systemLoad;
	private double [] processorLoad;
	
	private long fileDescriptorTotal, fileDescriptorAvailable, fileDescriptorUsed;
	private List<TritonFileSystem> fileSystems;

	public long getPhysicalMemoryAvailable() {
		return physicalMemoryAvailable;
	}

	public void setPhysicalMemoryAvailable(long physicalMemoryAvailable) {
		this.physicalMemoryAvailable = physicalMemoryAvailable;
	}

	public long getPhysicalMemoryUsed() {
		return physicalMemoryUsed;
	}

	public void setPhysicalMemoryUsed(long physicalMemoryUsed) {
		this.physicalMemoryUsed = physicalMemoryUsed;
	}

	public long getVirtualMemoryAvailable() {
		return virtualMemoryAvailable;
	}

	public void setVirtualMemoryAvailable(long virtualMemoryAvailable) {
		this.virtualMemoryAvailable = virtualMemoryAvailable;
	}

	public long getVirtualMemoryUsed() {
		return virtualMemoryUsed;
	}

	public void setVirtualMemoryUsed(long virtualMemoryUsed) {
		this.virtualMemoryUsed = virtualMemoryUsed;
	}

	public long getPhysicalMemoryTotal() {
		return physicalMemoryTotal;
	}

	public void setPhysicalMemoryTotal(long physicalMemoryTotal) {
		this.physicalMemoryTotal = physicalMemoryTotal;
	}

	public long getVirtualMemoryTotal() {
		return virtualMemoryTotal;
	}

	public void setVirtualMemoryTotal(long virtualMemoryTotal) {
		this.virtualMemoryTotal = virtualMemoryTotal;
	}

	public String getProcessorName() {
		return processorName;
	}

	public void setProcessorName(String processorName) {
		this.processorName = processorName;
	}

	public String getProcessorVendor() {
		return processorVendor;
	}

	public void setProcessorVendor(String processorVendor) {
		this.processorVendor = processorVendor;
	}

	public int getPhysicalProcessorCount() {
		return physicalProcessorCount;
	}

	public void setPhysicalProcessorCount(int physicalProcessorCount) {
		this.physicalProcessorCount = physicalProcessorCount;
	}

	public int getLogicalProcessorCount() {
		return logicalProcessorCount;
	}

	public void setLogicalProcessorCount(int logicalProcessorCount) {
		this.logicalProcessorCount = logicalProcessorCount;
	}

	public String getPlatform() {
		return platform;
	}

	public void setPlatform(String platform) {
		this.platform = platform;
	}

	public double getSystemLoad() {
		return systemLoad;
	}

	public void setSystemLoad(double systemLoad) {
		this.systemLoad = systemLoad;
	}

	public double[] getProcessorLoad() {
		return processorLoad;
	}

	public void setProcessorLoad(double[] processorLoad) {
		this.processorLoad = processorLoad;
	}

	public long getFileDescriptorTotal() {
		return fileDescriptorTotal;
	}

	public void setFileDescriptorTotal(long fileDescriptorTotal) {
		this.fileDescriptorTotal = fileDescriptorTotal;
	}

	public long getFileDescriptorAvailable() {
		return fileDescriptorAvailable;
	}

	public void setFileDescriptorAvailable(long fileDescriptorAvailable) {
		this.fileDescriptorAvailable = fileDescriptorAvailable;
	}

	public long getFileDescriptorUsed() {
		return fileDescriptorUsed;
	}

	public void setFileDescriptorUsed(long fileDescriptorUsed) {
		this.fileDescriptorUsed = fileDescriptorUsed;
	}
	
	public List<TritonFileSystem> getFileSystems() {
		return fileSystems;
	}

	public void setFileSystems(List<TritonFileSystem> fileSystems) {
		this.fileSystems = fileSystems;
	}

	public String getOs() {
		return os;
	}

	public void setOs(String os) {
		this.os = os;
	}

	public long getUptime() {
		return uptime;
	}

	public void setUptime(long uptime) {
		this.uptime = uptime;
	}

	public int getPhysicalProcessorPackageCount() {
		return physicalProcessorPackageCount;
	}

	public void setPhysicalProcessorPackageCount(int physicalProcessorPackageCount) {
		this.physicalProcessorPackageCount = physicalProcessorPackageCount;
	}

	public String getOsManufacturer() {
		return osManufacturer;
	}

	public void setOsManufacturer(String osManufacturer) {
		this.osManufacturer = osManufacturer;
	}

	public String getOsBuild() {
		return osBuild;
	}

	public void setOsBuild(String osBuild) {
		this.osBuild = osBuild;
	}

	public String getOsVersion() {
		return osVersion;
	}

	public void setOsVersion(String osVersion) {
		this.osVersion = osVersion;
	}

	public String getOsCodeName() {
		return osCodeName;
	}

	public void setOsCodeName(String osCodeName) {
		this.osCodeName = osCodeName;
	}

	public static class TritonFileSystem {
		private String mount, name, type, volume, id;
		private long spaceTotal, spaceUsed, spaceAvailable;
		public String getMount() {
			return mount;
		}
		public void setMount(String mount) {
			this.mount = mount;
		}
		public String getName() {
			return name;
		}
		public void setName(String name) {
			this.name = name;
		}
		public String getType() {
			return type;
		}
		public void setType(String type) {
			this.type = type;
		}
		public String getVolume() {
			return volume;
		}
		public void setVolume(String volume) {
			this.volume = volume;
		}
		public long getSpaceTotal() {
			return spaceTotal;
		}
		public void setSpaceTotal(long spaceTotal) {
			this.spaceTotal = spaceTotal;
		}
		public long getSpaceUsed() {
			return spaceUsed;
		}
		public void setSpaceUsed(long spaceUsed) {
			this.spaceUsed = spaceUsed;
		}
		public long getSpaceAvailable() {
			return spaceAvailable;
		}
		public void setSpaceAvailable(long spaceAvailable) {
			this.spaceAvailable = spaceAvailable;
		}
		public String getId() {
			return id;
		}
		public void setId(String id) {
			this.id = id;
		}
	}
}
