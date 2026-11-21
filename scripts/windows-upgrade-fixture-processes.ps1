# Fixture-only process enumeration without WMI. Returns actual kernel parent
# identities and command lines; no injected/synthetic OS process records.
function Initialize-PocketFixtureProcessSnapshot {
    if('PocketFixtureSnapshot' -as [type]){return}
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;
public static class PocketFixtureSnapshot {
 [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
 struct Entry { public uint size,usage,pid; public UIntPtr heap; public uint module,threads,parent; public int priority; public uint flags; [MarshalAs(UnmanagedType.ByValTStr,SizeConst=260)] public string name; }
 [StructLayout(LayoutKind.Sequential)] struct Text { public ushort length,max; public IntPtr buffer; }
 public class Row { public uint ProcessId,ParentProcessId; public string ExecutablePath,CommandLine,Name; }
 [DllImport("kernel32.dll",SetLastError=true)] static extern IntPtr CreateToolhelp32Snapshot(uint flags,uint pid);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool Process32FirstW(IntPtr h,ref Entry e);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool Process32NextW(IntPtr h,ref Entry e);
 [DllImport("kernel32.dll",SetLastError=true)] static extern IntPtr OpenProcess(uint access,bool inherit,uint pid);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool QueryFullProcessImageNameW(IntPtr h,uint flags,StringBuilder path,ref uint length);
 [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
 [DllImport("ntdll.dll")] static extern int NtQueryInformationProcess(IntPtr h,int kind,IntPtr buffer,int length,out int needed);
 public static Row[] Read(string root) {
   var result=new List<Row>(); var snapshot=CreateToolhelp32Snapshot(2,0);
   if(snapshot==new IntPtr(-1))throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
   try { var e=new Entry {size=(uint)Marshal.SizeOf(typeof(Entry))};
     if(!Process32FirstW(snapshot,ref e))throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
     do { var h=OpenProcess(0x1000,false,e.pid);if(h==IntPtr.Zero)continue;
       try { var path=new StringBuilder(32768);uint n=32768;
         if(!QueryFullProcessImageNameW(h,0,path,ref n))continue;
         if(!path.ToString().StartsWith(root+"\\",StringComparison.OrdinalIgnoreCase))continue;
         int needed;NtQueryInformationProcess(h,60,IntPtr.Zero,0,out needed);
         if(needed<=0||needed>1024*1024)throw new InvalidOperationException("Fixture command line unavailable");
         var buffer=Marshal.AllocHGlobal(needed);
         try { if(NtQueryInformationProcess(h,60,buffer,needed,out needed)!=0)continue;
           var text=(Text)Marshal.PtrToStructure(buffer,typeof(Text));
           result.Add(new Row {ProcessId=e.pid,ParentProcessId=e.parent,ExecutablePath=path.ToString(),Name=e.name,CommandLine=Marshal.PtrToStringUni(text.buffer,text.length/2)});
         } finally {Marshal.FreeHGlobal(buffer);}
       } finally {CloseHandle(h);}
     } while(Process32NextW(snapshot,ref e));
   } finally {CloseHandle(snapshot);}
   return result.ToArray();
 }
}
'@
}
