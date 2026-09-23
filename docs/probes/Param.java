import ctrmap.formats.garc.GARC;
import ctrmap.formats.scripts.*;
import java.io.File;
import java.util.*;
public class Param {
  public static void main(String[] a) throws Exception {
    GARC g=new GARC(new File(a[0]+"/a/0/1/3"));
    Map<Long,String> want=new LinkedHashMap<>();
    want.put(0xE5AB2CFAL,"PokePartyGetParam"); want.put(0x6EFC380EL,"PokePartySetParam"); want.put(0xD208EDAFL,"CallPokeSelect");
    List<PawnInstruction.Commands> cl=Arrays.asList(PawnInstruction.Commands.values());
    Set<Integer> pushOps=new HashSet<>();
    for(PawnInstruction.Commands c: PawnInstruction.Commands.values())
      if(c.name().startsWith("PUSH")) pushOps.add(c.ordinal());
    Map<String,List<int[]>> calls=new LinkedHashMap<>();
    Map<String,Set<Integer>> zonesFor=new LinkedHashMap<>();
    for(String s: want.values()){ calls.put(s,new ArrayList<int[]>()); zonesFor.put(s,new TreeSet<Integer>()); }
    for (int z=0; z<538; z++) {
      GFLPawnScript s=PartyParamProbe.zoneScript(g,z); if(s==null) continue;
      Map<Integer,String> slot=new HashMap<>();
      for(int i=0;i<s.natives.size();i++){int[] d=s.natives.get(i).data; long h=(d.length>1?d[1]:d[0])&0xFFFFFFFFL; if(want.containsKey(h)) slot.put(i,want.get(h));}
      if(slot.isEmpty()) continue;
      List<PawnInstruction> f=new ArrayList<>();
      for(PawnSubroutine sub:PawnDisassembler.disassembleScript(s)) f.addAll(sub.instructions);
      for(int i=0;i<f.size();i++){
        PawnInstruction ins=f.get(i);
        if(ins.getCommand()!=PawnInstruction.Commands.SYSREQ_N.ordinal()) continue;
        if(ins.argumentCells==null||ins.argumentCells.length<2) continue;
        String nm=slot.get(ins.argumentCells[0]); if(nm==null) continue;
        int nargs=ins.argumentCells[1]/4;
        // walk back collecting pushes; -1 means "not a literal constant"
        List<Integer> rev=new ArrayList<>();
        for(int k=i-1;k>=0 && rev.size()<nargs;k--){
          PawnInstruction p=f.get(k);
          if(!pushOps.contains(p.getCommand())) break;
          boolean isConst=cl.get(p.getCommand()).name().endsWith("_C");
          rev.add(isConst && p.argumentCells!=null && p.argumentCells.length>0 ? p.argumentCells[0] : -1);
        }
        if(rev.size()<nargs) continue;
        Collections.reverse(rev);           // pushes are reverse-order => now arg1..argN
        int[] arr=new int[rev.size()]; for(int q=0;q<arr.length;q++) arr[q]=rev.get(q);
        calls.get(nm).add(arr); zonesFor.get(nm).add(z);
      }
    }
    for(String nm: want.values()){
      List<int[]> cs=calls.get(nm);
      System.out.println("\n=== "+nm+"   "+cs.size()+" call(s) in "+zonesFor.get(nm).size()+" zone(s)");
      if(cs.isEmpty()) continue;
      int nargs=cs.get(0).length;
      for(int p=0;p<nargs;p++){
        TreeMap<Integer,Integer> h=new TreeMap<>();
        for(int[] c: cs){ if(p<c.length){ Integer x=h.get(c[p]); h.put(c[p],x==null?1:x+1);} }
        StringBuilder sb=new StringBuilder("   arg"+(p+1)+": ");
        for(Map.Entry<Integer,Integer> e:h.entrySet()) sb.append(e.getKey()==-1?"<var>":String.valueOf(e.getKey())).append("x").append(e.getValue()).append("  ");
        System.out.println(sb);
      }
      System.out.println("   zones: "+zonesFor.get(nm));
    }
  }
}
