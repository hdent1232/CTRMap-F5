package ctrmap.formats.h3d;

import java.util.*;

/**
 * Appends a MESH + MATERIAL copied from a donor map model into a target map
 * model - the content-section rebuild that unlocks cross-area prefabs and
 * new-material OBJ imports. Implements the byte-level recipe measured across
 * all 857 retail models (canonical contents layout, seven ordered insertions,
 * full reloc-driven pointer fixup, string-pool re-sort, byte-exact patricia
 * tree rebuild - the tree algorithm reproduces all 30,538 retail trees).
 *
 * <p>The donor mesh's command streams, material params, metadata and buffers
 * are copied verbatim with only position-dependent words patched, so the
 * appended mesh renders exactly like it did in the donor. The donor's texture
 * names ride along in the material header; when the target lives in a
 * different AREA, carry the textures with BchTexturePack.importTextures first.
 *
 * <p>Refuses skinned donor submeshes when the target has no skeleton, and
 * duplicate material names. Callers should verify the result with
 * {@link BchModelVerifier} (aux-LUT regions legitimately report only their
 * pre-existing command-tail note).
 */
public class BchModelAppender {

    static int le32(byte[] b,int o){return (b[o]&0xFF)|((b[o+1]&0xFF)<<8)|((b[o+2]&0xFF)<<16)|((b[o+3]&0xFF)<<24);}
    static void poke(byte[] b,int o,int v){b[o]=(byte)v;b[o+1]=(byte)(v>>8);b[o+2]=(byte)(v>>16);b[o+3]=(byte)(v>>24);}
    static void poke16(byte[] b,int o,int v){b[o]=(byte)v;b[o+1]=(byte)(v>>8);}
    static String str(byte[] b,int abs){StringBuilder sb=new StringBuilder();for(int p=abs;p<b.length&&b[p]!=0;p++)sb.append((char)(b[p]&0xFF));return sb.toString();}
    static int align(int v,int a){return (v+a-1)/a*a;}

    // ---------------- patricia (validated byte-exact vs all 30538 GF trees) ----------------
    static class Node { long refBit; int left, right; String name; }
    static boolean getBit(String name, long bit){
        int pos=(int)(bit>>>3);
        if (name!=null && pos<name.length()) return ((name.charAt(pos)>>(int)(bit&7))&1)!=0;
        return false;
    }
    static int traverse(String name, List<Node> nodes, Node[] rootOut, long bit){
        Node root=nodes.get(0); int out=root.left; Node left=nodes.get(out);
        while (root.refBit>left.refBit && left.refBit>bit){
            out=getBit(name,left.refBit)?left.right:left.left;
            root=left; left=nodes.get(out);
        }
        rootOut[0]=root; return out;
    }
    static List<Node> buildTree(List<String> names){
        List<Node> nodes=new ArrayList<>();
        Node root=new Node(); root.refBit=0xFFFFFFFFL; nodes.add(root);
        int maxLen=0; for (String t:names) maxLen=Math.max(maxLen,t.length());
        Node[] rootOut=new Node[1];
        for (String t:names){
            long bit=(maxLen<<3)-1;
            int index=traverse(t,nodes,rootOut,0);
            while (getBit(nodes.get(index).name,bit)==getBit(t,bit)) if (--bit<0) throw new IllegalArgumentException("dup "+t);
            Node n=new Node(); n.name=t; n.refBit=bit;
            if (getBit(t,bit)){ n.left=traverse(t,nodes,rootOut,bit); n.right=nodes.size(); }
            else { n.left=nodes.size(); n.right=traverse(t,nodes,rootOut,bit); }
            Node rt=rootOut[0];
            if (getBit(t,rt.refBit)) rt.right=nodes.size(); else rt.left=nodes.size();
            nodes.add(n);
        }
        return nodes;
    }

    // decoded reloc entry
    static class RE { int flag, tgt, src, ptrLoc, word; }
    static List<RE> decode(BchMapModel m, byte[] raw){
        List<RE> out=new ArrayList<>();
        for (int v : m.reloc){
            RE e=new RE();
            e.flag=(v>>>25)&0x7F; e.tgt=e.flag&0xF; e.src=e.flag>>>4;
            int off=v&0x1FFFFFF;
            int base = e.src==0?m.contentsAddr: e.src==2?m.commandsAddr: -1;
            if (base<0) throw new IllegalStateException("reloc src "+e.src);
            e.ptrLoc = base + (e.tgt==1?off:off*4);
            e.word = le32(raw, e.ptrLoc);
            out.add(e);
        }
        return out;
    }
    static int encode(int flag, int ptrLoc, int srcBase, int tgt){
        int off = (tgt==1)? (ptrLoc-srcBase) : (ptrLoc-srcBase)/4;
        return (off & 0x1FFFFFF) | (flag<<25);
    }

    /**
     * Where, in the donor file, everything an append copies lives. Absolute
     * file offsets, computed from the donor alone by {@link #locateDonor};
     * public so a suite can ask for them without appending anything.
     */
    public static final class DonorSpans {
        /** The material the donor mesh uses. */
        public int mat;
        /** Its 0x2C material header, and the mesh's 0x38 header. */
        public int matHdr, meshHdr;
        /** The material's parameter block [beg, end). */
        public int paramsBeg, paramsEnd;
        /** The mesh's submesh block [beg, end). */
        public int subBeg, subEnd;
        /** The material's command stream: fragment commands from matCmdBeg, texture commands [texCmdBeg, texCmdEnd). */
        public int matCmdBeg, texCmdBeg, texCmdEnd;
        /** The mesh's command stream: enable commands from meshCmdBeg, disable commands [disCmdBeg, disCmdEnd). */
        public int meshCmdBeg, disCmdBeg, disCmdEnd;
        /** The mesh's vertex and index buffers in the raw section; idxFlag is the index pointer's reloc flag, 0x27 for 16-bit indices, 0x28 for 8-bit. */
        public int vtx, vtxSize, idx, idxSize, idxFlag;
        /** Pointers to the material header's three texture-name strings, or null where the slot is empty. */
        public Integer tex0, tex1, tex2;
    }

    /**
     * Finds the donor spans for submesh {@code dj}. Every object start in the
     * contents section (material parameter blocks, submesh blocks, and where
     * the metadata tail begins) is collected, so a block ends where the next
     * object starts. Refuses a skinned submesh, and a material whose command
     * streams are not in the expected order.
     */
    public static DonorSpans locateDonor(byte[] D, BchMapModel d, int dj){
        DonorSpans s=new DonorSpans();
        s.mat=d.meshes.get(dj)[5];
        s.paramsBeg=d.materialParamOffsets.get(s.mat);
        TreeSet<Integer> dObj=new TreeSet<>();
        for (int i=0;i<d.matCount;i++) dObj.add(d.materialParamOffsets.get(i));
        for (int j=0;j<d.meshCount;j++) dObj.add(d.meshes.get(j)[3]);
        int dmmv=d.ptr(d.modelMetaPtr), dmmc=le32(D,d.modelMetaPtr+4);
        int dTail=0x44+d.contentsLen;
        for (int k=0;k<dmmc;k++){ int dp=d.ptr(dmmv+k*0xC+8); if (dp!=0&&dp<dTail) dTail=dp; }
        dObj.add(dTail);
        s.paramsEnd=dObj.higher(s.paramsBeg);
        s.subBeg=d.meshes.get(dj)[3];
        s.subEnd=dObj.higher(s.subBeg);

        // donor submesh must not be skinned (+0 skinningMode u16, +2 nodeIdCount u16):
        // its bone indices reference the DONOR's bone list, which the target does not
        // have - even a target WITH a skeleton has different bones (verified crash:
        // donor bone index 5 vs a 2-bone target). Rigid-skinned interior props are
        // therefore not appendable in v1; same-material fast paths still handle them.
        if ((le32(D,s.subBeg)&0xFFFFFFFF)!=0)
            throw new IllegalStateException("donor submesh is skinned (bone-dependent) - not supported");
        s.matHdr=d.matValuesPtr+s.mat*0x2C;
        s.meshHdr=d.meshes.get(dj)[0];

        // donor command blocks
        s.matCmdBeg=d.ptr(s.paramsBeg+0xC8);
        s.texCmdBeg=d.ptr(s.matHdr+0x10);
        s.texCmdEnd=s.texCmdBeg+4*le32(D,s.matHdr+0x14);
        s.meshCmdBeg=d.meshes.get(dj)[1];
        s.disCmdBeg=d.ptr(s.meshHdr+0x18);
        s.disCmdEnd=s.disCmdBeg+4*le32(D,s.meshHdr+0x1C);
        if (!(s.matCmdBeg<s.texCmdBeg && s.texCmdBeg<=s.texCmdEnd)) throw new IllegalStateException("donor mat cmd order");

        // donor mat hdr strings
        if (d.ptr(s.matHdr+0x1C)!=0) s.tex0=d.ptr(s.matHdr+0x1C);
        if (d.ptr(s.matHdr+0x20)!=0) s.tex1=d.ptr(s.matHdr+0x20);
        if (d.ptr(s.matHdr+0x24)!=0) s.tex2=d.ptr(s.matHdr+0x24);

        // donor buffers
        TreeSet<Integer> dBufs=new TreeSet<>();
        for (int[] vb : d.vtxBuffers) dBufs.add(vb[0]);
        for (int[] ib : d.idxBuffers) dBufs.add(ib[0]);
        s.vtx=d.vtxBuffers.get(0)[0]; // per-mesh: find by cmd loc
        { int loc=d.meshes.get(dj)[1]+0x30;
          for (int[] vb : d.vtxBuffers) if (vb[1]==loc) s.vtx=vb[0];
          int sc=d.ptr(d.meshes.get(dj)[3]+0x2C)+0x10;
          for (int[] ib : d.idxBuffers) if (ib[1]==sc){ s.idx=ib[0]; s.idxFlag=(ib[2]==2)?0x27:0x28; }
        }
        int dRawEnd=d.rawDataAddr+d.rawDataLen;
        Integer q; q=dBufs.higher(s.vtx); int dVe=q==null?dRawEnd:q;
        q=dBufs.higher(s.idx); int dIe=q==null?dRawEnd:q;
        s.vtxSize=dVe-s.vtx; s.idxSize=dIe-s.idx;
        return s;
    }

    /**
     * A string pool rebuilt for the appended model: the target's strings plus
     * the ones the append needs, sorted, NUL-separated, first byte NUL - the
     * layout every retail pool has. Built by {@link #rebuildStrings}.
     */
    public static final class StringPool {
        public byte[] bytes;
        /** string -> its offset in the new pool */
        public Map<String,Integer> offset;
        /** old pool offset -> new pool offset, for every string the target had (and 0 -> 0) */
        public Map<Integer,Integer> remap;
    }

    public static StringPool rebuildStrings(byte[] T, BchMapModel t, Collection<String> needed){
        TreeSet<String> set=new TreeSet<>(needed);
        { int s=t.stringsAddr+1, end=t.stringsAddr+t.stringsLen;
          while (s<end){ String v=str(T,s); set.add(v); s+=v.length()+1; } }
        StringPool sp=new StringPool();
        sp.offset=new HashMap<>();
        int so=1;
        StringBuilder poolB=new StringBuilder("\0");
        for (String v : set){ sp.offset.put(v,so); poolB.append(v).append('\0'); so+=v.length()+1; }
        sp.bytes=new byte[so];
        for (int i=0;i<so;i++) sp.bytes[i]=(byte)poolB.charAt(i);
        // old->new offset remap
        sp.remap=new HashMap<>();
        { int s=t.stringsAddr+1, end=t.stringsAddr+t.stringsLen;
          sp.remap.put(0,0);
          while (s<end){ String v=str(T,s); sp.remap.put(s-t.stringsAddr, sp.offset.get(v)); s+=v.length()+1; } }
        return sp;
    }

    /**
     * Appends donor submesh {@code dj} (and its material, named {@code newName})
     * to the target. In order: parse both and take the target's landmarks;
     * {@link #locateDonor}; gather the strings the append needs and
     * {@link #rebuildStrings}; rebuild the two name trees; plan the seven
     * contents insertions and the command/raw layouts; assemble contents,
     * commands and raw; rewrite every relocation; repair the layer boundary
     * words; write the file.
     */
    public static byte[] append(byte[] T, byte[] D, int dj, String newName){
        BchMapModel t=new BchMapModel(T), d=new BchMapModel(D);
        if (!t.validate().isEmpty() || !d.validate().isEmpty()) throw new IllegalStateException("parse problems");
        int C=0x44;
        int N=t.matCount, M=t.meshCount;

        // ---- target landmarks ----
        int d1t=t.ptr(C+12+8), d1v=t.ptr(C+12);
        int matTree=t.ptr(t.modelPtr+0x3C);
        int matArr=t.matValuesPtr, meshArr=t.meshesPtr;
        int P6=Integer.MAX_VALUE; for (int j=0;j<M;j++) P6=Math.min(P6, t.meshes.get(j)[3]);
        int mmv=t.ptr(t.modelMetaPtr), mmc=le32(T,t.modelMetaPtr+4);
        int P7=C+t.contentsLen;
        for (int k=0;k<mmc;k++){ int dp=t.ptr(mmv+k*0xC+8); if (dp!=0&&dp<P7) P7=dp; }
        String tModelName=str(T, t.modelNamePtr);

        // ---- donor landmarks ----
        DonorSpans ds=locateDonor(D, d, dj);
        String newFull=newName+"@"+tModelName;
        int matCmdSize=ds.texCmdEnd-ds.matCmdBeg, meshCmdSize=ds.disCmdEnd-ds.meshCmdBeg;
        int vtxSize=ds.vtxSize, idxSize=ds.idxSize;

        // ---- string pool ----
        for (int i=0;i<N;i++) if (t.getMaterialName(i).equals(newName)) throw new IllegalArgumentException("name exists");
        // donor strings needed: role-translated
        List<RE> dre=decode(d,D);
        List<RE> dBlockEntries=new ArrayList<>();
        for (RE e : dre){
            if (e.src==0 && ((e.ptrLoc>=ds.paramsBeg&&e.ptrLoc<ds.paramsEnd)||(e.ptrLoc>=ds.subBeg&&e.ptrLoc<ds.subEnd))) dBlockEntries.add(e);
        }
        TreeSet<String> needed=new TreeSet<>();
        for (RE e : dBlockEntries) if (e.tgt==1){
            String v = (e.ptrLoc==ds.paramsBeg+0x108)? newFull : str(D, d.stringsAddr+e.word);
            needed.add(v);
        }
        if (ds.tex0!=null) needed.add(str(D,ds.tex0));
        if (ds.tex1!=null) needed.add(str(D,ds.tex1));
        if (ds.tex2!=null) needed.add(str(D,ds.tex2));
        needed.add(newName); needed.add(newFull);
        StringPool sp=rebuildStrings(T, t, needed);
        byte[] newStrings=sp.bytes;
        Map<String,Integer> strOff=sp.offset;
        Map<Integer,Integer> strRemap=sp.remap;

        // ---- trees ----
        List<String> fullNames=new ArrayList<>(), matNames=new ArrayList<>();
        for (int i=1;i<=N;i++) fullNames.add(str(T, t.ptr(d1t+i*0xC+8)));
        for (int i=0;i<N;i++) matNames.add(t.getMaterialName(i));
        List<Node> nD1=buildTree(concat(fullNames,newFull));
        List<Node> nMT=buildTree(concat(matNames,newName));

        // ---- contents insertions ----
        int lam=le32(D,ds.meshHdr+4)>>>24;
        int[] endOff={0x4C,0x54,0x5C,0x64};
        int[] begOff={0x48,0x50,0x58,0x60};
        int P5=t.ptr(t.modelPtr+endOff[lam]);
        //old per-layer mesh counts (for the boundary repair pass below - the generic
        //inclusive delta mis-shifts boundary words that coincide with P5 when the
        //donor layer's slice, or slices around it, are empty)
        int[] layerCnt=new int[4];
        for (int k=0;k<4;k++) layerCnt[k]=(t.ptr(t.modelPtr+endOff[k])-t.ptr(t.modelPtr+begOff[k]))/0x38;
        layerCnt[lam]++;

        int[] insPos ={ d1v, d1v+4*N, matArr, meshArr, P5, P6, P7 };
        int[] insSize={ 0xC, 4,       0xC,    0x2C,    0x38, ds.paramsEnd-ds.paramsBeg, ds.subEnd-ds.subBeg };
        // new absolute starts (contents) of each insertion
        int[] newStart=new int[7];
        { int acc=0;
          for (int k=0;k<7;k++){ newStart[k]=insPos[k]+acc; acc+=insSize[k]; } }
        int totalIns=0; for (int s2 : insSize) totalIns+=s2;
        int newMatHdrAbs=newStart[3], newMeshHdrAbs=newStart[4], newMatBlkAbs=newStart[5], newMeshBlkAbs=newStart[6];
        int newParamsAbs=newMatBlkAbs, newSubAbs=newMeshBlkAbs;
        int newModelAbs=t.modelPtr+0x10; // shifted by insertions A (dict1 tree node) + B (dict1 value)

        // contents delta function (inclusive)
        java.util.function.IntUnaryOperator cdelta = x -> { int a=0; for (int k=0;k<7;k++) if (insPos[k]<=x) a+=insSize[k]; return a; };

        // command layout
        int Q1=Integer.MAX_VALUE; for (int j=0;j<M;j++) Q1=Math.min(Q1, t.meshes.get(j)[1]);
        int Q1rel=Q1-t.commandsAddr;
        int oldCmdLen=t.commandsLen;
        int newMatCmdRel=Q1rel, newMeshCmdRel=oldCmdLen+matCmdSize;
        java.util.function.IntUnaryOperator cmdDelta = x -> x>=Q1rel? matCmdSize:0;
        int newCmdLen=oldCmdLen+matCmdSize+meshCmdSize;

        // raw layout
        int rawLen=t.rawDataLen;
        int vtxGroupEnd=rawLen, idx16GroupEnd=rawLen;
        { int minIdx=Integer.MAX_VALUE, minIdx8=Integer.MAX_VALUE;
          for (int[] ib : t.idxBuffers){ minIdx=Math.min(minIdx, ib[0]); if (ib[2]==1) minIdx8=Math.min(minIdx8, ib[0]); }
          if (minIdx!=Integer.MAX_VALUE) vtxGroupEnd=minIdx-t.rawDataAddr;
          if (minIdx8!=Integer.MAX_VALUE) idx16GroupEnd=minIdx8-t.rawDataAddr; }
        int insVtxPos=vtxGroupEnd;
        int insIdxPos=(ds.idxFlag==0x27)? idx16GroupEnd : rawLen;
        java.util.function.IntUnaryOperator rawDelta = x -> { int a=0; if (insVtxPos<=x) a+=vtxSize; if (insIdxPos<=x) a+=idxSize; return a; };
        int newVtxOff=insVtxPos, newIdxOff=insIdxPos+vtxSize;
        int newRawLen=rawLen+vtxSize+idxSize;

        // ---- assemble contents ----
        byte[] oldC=Arrays.copyOfRange(T, C, C+t.contentsLen);
        // in-place tree node overwrites (nodes 0..N keep OLD string offsets; generic pass remaps)
        for (int n=0;n<=N;n++){
            writeNode(oldC, d1t-C+n*0xC, nD1.get(n), n==0?-1: (le32(T, d1t+n*0xC+8)));
            writeNode(oldC, matTree-C+n*0xC, nMT.get(n), n==0?-1: (le32(T, matTree+n*0xC+8)));
        }
        byte[] newC=new byte[t.contentsLen+totalIns];
        { int src=0, dst2=0;
          for (int k=0;k<7;k++){
              int copy=(insPos[k]-C)-src;
              System.arraycopy(oldC,src,newC,dst2,copy); src+=copy; dst2+=copy;
              dst2+=insSize[k]; // leave hole
          }
          System.arraycopy(oldC,src,newC,dst2,oldC.length-src);
        }
        // fill: dict1 tree node N+1 / matTree node N+1 (NEW string offsets, final)
        writeNode(newC, newStart[0]-C, nD1.get(N+1), strOff.get(newFull));
        writeNode(newC, newStart[2]-C, nMT.get(N+1), strOff.get(newName));
        // dict1.values[N]
        poke(newC, newStart[1]-C, newParamsAbs-C);
        // new mat hdr (donor bytes, patch)
        System.arraycopy(D, ds.matHdr, newC, newMatHdrAbs-C, 0x2C);
        poke(newC, newMatHdrAbs-C+0x00, newParamsAbs-C);
        poke(newC, newMatHdrAbs-C+0x10, newMatCmdRel+(ds.texCmdBeg-ds.matCmdBeg));
        poke(newC, newMatHdrAbs-C+0x18, newParamsAbs-C+0x110);
        poke(newC, newMatHdrAbs-C+0x1C, ds.tex0==null?0:strOff.get(str(D,ds.tex0)));
        poke(newC, newMatHdrAbs-C+0x20, ds.tex1==null?0:strOff.get(str(D,ds.tex1)));
        poke(newC, newMatHdrAbs-C+0x24, ds.tex2==null?0:strOff.get(str(D,ds.tex2)));
        poke(newC, newMatHdrAbs-C+0x28, strOff.get(newName));
        // new mesh hdr
        System.arraycopy(D, ds.meshHdr, newC, newMeshHdrAbs-C, 0x38);
        poke(newC, newMeshHdrAbs-C+0x00, N);
        poke(newC, newMeshHdrAbs-C+0x08, newMeshCmdRel+(ds.meshCmdBeg-ds.meshCmdBeg));
        poke(newC, newMeshHdrAbs-C+0x10, newSubAbs-C);
        poke(newC, newMeshHdrAbs-C+0x18, newMeshCmdRel+(ds.disCmdBeg-ds.meshCmdBeg));
        poke(newC, newMeshHdrAbs-C+0x2C, newModelAbs-C);
        poke(newC, newMeshHdrAbs-C+0x34, newSubAbs-C+0x34);
        // donor blocks verbatim
        System.arraycopy(D, ds.paramsBeg, newC, newMatBlkAbs-C, ds.paramsEnd-ds.paramsBeg);
        System.arraycopy(D, ds.subBeg, newC, newMeshBlkAbs-C, ds.subEnd-ds.subBeg);
        // counts
        poke(newC, 12+4, N+1);                    // content dict1 count (dictTable+0x10 rel to C)
        poke(newC, newModelAbs-C+0x38, N+1);
        poke(newC, newModelAbs-C+0x44, M+1);

        // ---- assemble commands ----
        byte[] newCmd=new byte[newCmdLen];
        System.arraycopy(T, t.commandsAddr, newCmd, 0, Q1rel);
        System.arraycopy(D, ds.matCmdBeg, newCmd, Q1rel, matCmdSize);
        System.arraycopy(T, t.commandsAddr+Q1rel, newCmd, Q1rel+matCmdSize, oldCmdLen-Q1rel);
        System.arraycopy(D, ds.meshCmdBeg, newCmd, newMeshCmdRel, meshCmdSize);

        // ---- assemble raw ----
        byte[] newRaw=new byte[newRawLen];
        System.arraycopy(T, t.rawDataAddr, newRaw, 0, insVtxPos);
        System.arraycopy(D, ds.vtx, newRaw, insVtxPos, vtxSize);
        System.arraycopy(T, t.rawDataAddr+insVtxPos, newRaw, insVtxPos+vtxSize, insIdxPos-insVtxPos);
        System.arraycopy(D, ds.idx, newRaw, insIdxPos+vtxSize, idxSize);
        System.arraycopy(T, t.rawDataAddr+insIdxPos, newRaw, insIdxPos+vtxSize+idxSize, rawLen-insIdxPos);

        // ---- reloc rebuild ----
        List<RE> tre=decode(t,T);
        List<int[]> outTrios=new ArrayList<>();  // encoded words, CMD-source
        List<Integer> outRest=new ArrayList<>();
        int newContentsAddr=C, newStringsAddr=C+newC.length;
        int newCommandsAddr=align(newStringsAddr+newStrings.length,0x10);
        // old entries
        for (RE e : tre){
            int nl, w=e.word, nw;
            if (e.src==2){
                int rel=e.ptrLoc-t.commandsAddr;
                nl=rel+cmdDelta.applyAsInt(rel);
                switch (e.flag){
                    case 0x2E: nw=0; break;
                    case 0x26: nw=w+rawDelta.applyAsInt(w); break;
                    case 0x27: case 0x28: { int off=w&0x7FFFFFFF; nw=(w&0x80000000)|(off+rawDelta.applyAsInt(off)); break; }
                    default: throw new IllegalStateException("cmd flag "+Integer.toHexString(e.flag));
                }
                poke(newCmd, nl, nw);
                outTrios.add(new int[]{encode(e.flag, newCommandsAddr+nl, newCommandsAddr, e.tgt)});
            } else {
                int rel=e.ptrLoc-C;
                nl=rel+cdelta.applyAsInt(e.ptrLoc);
                switch (e.tgt){
                    case 0: nw=w+cdelta.applyAsInt(w+C); break;
                    case 1: { Integer r2=strRemap.get(w); if (r2==null) throw new IllegalStateException("str off "+w); nw=r2; break; }
                    case 2: case 3: nw=w+cmdDelta.applyAsInt(w); break;
                    default: throw new IllegalStateException("cont tgt "+e.tgt);
                }
                poke(newC, nl, nw);
                outRest.add(encode(e.flag, C+nl, C, e.tgt));
            }
        }
        // new trio (donor mesh CMD entries)
        for (RE e : dre){
            if (e.src!=2) continue;
            if (e.ptrLoc<ds.meshCmdBeg||e.ptrLoc>=ds.disCmdEnd) continue;
            int nl=newMeshCmdRel+(e.ptrLoc-d.commandsAddr-(ds.meshCmdBeg-d.commandsAddr));
            int nw;
            switch (e.flag){
                case 0x2E: nw=0; break;
                case 0x26: nw=newVtxOff; break;
                case 0x27: case 0x28: nw=(e.word&0x80000000)|newIdxOff; break;
                default: throw new IllegalStateException();
            }
            poke(newCmd, nl, nw);
            outTrios.add(new int[]{encode(e.flag, newCommandsAddr+nl, newCommandsAddr, e.tgt)});
        }
        // donor block entries
        for (RE e : dBlockEntries){
            boolean inMat=e.ptrLoc>=ds.paramsBeg&&e.ptrLoc<ds.paramsEnd;
            int base=inMat?ds.paramsBeg:ds.subBeg, nbase=inMat?newMatBlkAbs:newMeshBlkAbs;
            int nl=(nbase-C)+(e.ptrLoc-base);
            int nw;
            switch (e.tgt){
                case 0: { int abs=e.word+0x44;
                          if (abs<base||abs>= (inMat?ds.paramsEnd:ds.subEnd)) throw new IllegalStateException("cross-block ptr");
                          nw=(nbase-C)+(abs-base); break; }
                case 1: { String v=(e.ptrLoc==ds.paramsBeg+0x108)?newFull:str(D,d.stringsAddr+e.word); nw=strOff.get(v); break; }
                case 2: case 3: {
                          if (e.word>=ds.matCmdBeg-d.commandsAddr && e.word<ds.texCmdEnd-d.commandsAddr) nw=newMatCmdRel+(e.word-(ds.matCmdBeg-d.commandsAddr));
                          else if (e.word>=ds.meshCmdBeg-d.commandsAddr && e.word<ds.disCmdEnd-d.commandsAddr) nw=newMeshCmdRel+(e.word-(ds.meshCmdBeg-d.commandsAddr));
                          else throw new IllegalStateException("donor cmd word outside blocks");
                          break; }
                default: throw new IllegalStateException("donor tgt "+e.tgt);
            }
            poke(newC, nl, nw);
            outRest.add(encode(e.flag, C+nl, C, e.tgt));
        }
        // synthesized entries
        // dict1 tree node N+1 name (STR), matTree node N+1 name (STR), dict1.values[N] (CONT)
        outRest.add(encode(0x01, newStart[0]+8, C, 1));
        outRest.add(encode(0x01, newStart[2]+8, C, 1));
        outRest.add(encode(0x00, newStart[1], C, 0));
        // new mat hdr
        int h=newMatHdrAbs;
        outRest.add(encode(0x00, h+0x00, C, 0));
        outRest.add(encode(0x02, h+0x10, C, 2));
        outRest.add(encode(0x00, h+0x18, C, 0));
        if (ds.tex0!=null) outRest.add(encode(0x01, h+0x1C, C, 1));
        if (ds.tex1!=null) outRest.add(encode(0x01, h+0x20, C, 1));
        if (ds.tex2!=null) outRest.add(encode(0x01, h+0x24, C, 1));
        outRest.add(encode(0x01, h+0x28, C, 1));
        // new mesh hdr
        int g=newMeshHdrAbs;
        outRest.add(encode(0x02, g+0x08, C, 2));
        outRest.add(encode(0x00, g+0x10, C, 0));
        outRest.add(encode(0x02, g+0x18, C, 2));
        outRest.add(encode(0x00, g+0x2C, C, 0));
        outRest.add(encode(0x00, g+0x34, C, 0));

        // ---- layer-boundary repair ----
        // overwrite the 8 begin/end words with directly computed values: the new
        // meshes array starts at old meshArr + (dict1 node + dict1 value + matTree
        // node + material header) and each layer spans 0x38*count (donor layer +1)
        {
            int cur = (meshArr + 0xC + 4 + 0xC + 0x2C) - C;
            for (int k = 0; k < 4; k++) {
                poke(newC, newModelAbs - C + begOff[k], cur);
                cur += 0x38 * layerCnt[k];
                poke(newC, newModelAbs - C + endOff[k], cur);
            }
        }

        // ---- final file ----
        int newRawAddr=align(newCommandsAddr+newCmdLen,0x80);
        int relocCount=outTrios.size()+outRest.size();
        int newRelocAddr=align(newRawAddr+newRawLen,0x80);
        int fileLen=align(newRelocAddr+relocCount*4,0x80);
        byte[] out=new byte[fileLen];
        // header
        System.arraycopy(T,0,out,0,0x44);
        poke(out,8,C);
        poke(out,12,newStringsAddr);
        poke(out,16,newCommandsAddr);
        poke(out,20,newRawAddr);
        poke(out,24,newRelocAddr);
        poke(out,28,newRelocAddr);
        poke(out,32,newC.length);
        poke(out,36,newStrings.length);
        poke(out,40,newCmdLen);
        poke(out,44,newRawLen);
        poke(out,48,0);
        poke(out,52,relocCount*4);
        poke(out,56,(2*(M+1))*4);   // uninitDataSectionLength = 4*addressCount
        poke(out,60,0);
        out[64]=1;
        poke16(out,66,2*(M+1));
        System.arraycopy(newC,0,out,C,newC.length);
        System.arraycopy(newStrings,0,out,newStringsAddr,newStrings.length);
        System.arraycopy(newCmd,0,out,newCommandsAddr,newCmdLen);
        System.arraycopy(newRaw,0,out,newRawAddr,newRawLen);
        int rp=newRelocAddr;
        for (int[] e : outTrios){ poke(out,rp,e[0]); rp+=4; }
        for (int e : outRest){ poke(out,rp,e); rp+=4; }
        return out;
    }

    static void writeNode(byte[] b, int off, Node n, int nameOff){
        poke(b,off,(int)n.refBit);
        poke16(b,off+4,n.left);
        poke16(b,off+6,n.right);
        poke(b,off+8, nameOff<0?0:nameOff);
    }
    static List<String> concat(List<String> a, String b){ List<String> r=new ArrayList<>(a); r.add(b); return r; }
}
