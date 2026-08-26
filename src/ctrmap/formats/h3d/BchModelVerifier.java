package ctrmap.formats.h3d;

import java.util.*;

/**
 * Strict structural verifier for ORAS map-model BCH files: every layout
 * invariant measured across the 857-model corpus (section chain + alignment,
 * dict/tree/model/material/mesh adjacency, layer partition, command-stream
 * coverage, raw-data buffer grouping, reloc trio ordering). An UNMODIFIED
 * retail model verifies clean except the 18 aux-LUT regions, which report only
 * their command-tail contiguity note - writers (BchModelAppender) use that as
 * the acceptance rule for their output.
 */


/** Strict structural verifier (all learned invariants, address-order based). */
public class BchModelVerifier {
    static byte[] raw;
    static BchMapModel m;
    static List<String> fails;
    static int le32(int o){return (raw[o]&0xFF)|((raw[o+1]&0xFF)<<8)|((raw[o+2]&0xFF)<<16)|((raw[o+3]&0xFF)<<24);}
    static void chk(boolean c,String w){ if(!c) fails.add(w); }

    public static List<String> verify(byte[] bch){
        fails=new ArrayList<>();
        raw=bch; m=new BchMapModel(bch);
        fails.addAll(m.validate());
        int C=m.contentsAddr;
        // sections
        chk(m.stringsAddr==C+m.contentsLen,"stringsAddr");
        chk(m.commandsAddr==((m.stringsAddr+m.stringsLen+0xF)&~0xF),"commandsAddr align16");
        chk(m.rawDataAddr==((m.commandsAddr+m.commandsLen+0x7F)&~0x7F),"rawAddr align80");
        chk(m.relocAddr==((m.rawDataAddr+m.rawDataLen+0x7F)&~0x7F),"relocAddr align80");
        chk(m.rawExtAddr==m.relocAddr&&m.rawExtLen==0,"rawExt");
        chk(raw.length==((m.relocAddr+m.relocLen+0x7F)&~0x7F),"fileLen align80");
        chk(m.uninitCmd==0&&m.flagsByte==1,"uninit/flags");
        chk(m.addressCount==2*m.meshCount&&m.uninitData==8*m.meshCount,"addressCount/uninitData");
        int N=m.matCount, M=m.meshCount;
        // dict region
        int d0t=m.ptr(C+8), d0v=m.ptr(C), d1t=m.ptr(C+12+8), d1v=m.ptr(C+12);
        chk(d0t==C+0xB4,"d0t"); chk(d0v==d0t+0x18,"d0v"); chk(d1t==d0v+4,"d1t"); chk(d1v==d1t+0xC*(N+1),"d1v");
        int p=d1v+4*N;
        for (int d=2;d<15;d++){
            int tp=m.ptr(C+d*12+8), cnt=le32(C+d*12+4);
            chk(tp==p,"dict"+d+" tree");
            p=tp+0xC*(cnt+1);
            if (cnt>0){ int vp=m.ptr(C+d*12); chk(vp==p,"aux"+d+" vals"); p=vp+4*cnt; }
        }
        chk(m.modelPtr==p,"modelPtr");
        // model region
        int vis=m.ptr(m.modelPtr+0x7C), visCnt=le32(m.modelPtr+0x80);
        int mnt=m.ptr(m.modelPtr+0x8C), mnc=le32(m.modelPtr+0x88);
        int mt=m.ptr(m.modelPtr+0x3C);
        chk(vis==m.modelPtr+0x98,"vis pos");
        int visSize=4*((visCnt+31)/32);
        int afterVis=vis+visSize+((mnt==0)?0xC:0);   // null meshNodes tree still occupies a root stub
        if (mnt!=0){ chk(mnt==vis+visSize,"mnt pos"); afterVis=mnt+0xC*(mnc+1); }
        chk(mt==afterVis,"matTree pos");
        chk(m.matValuesPtr==mt+0xC*(N+1),"matArr pos");
        chk(m.meshesPtr==m.matValuesPtr+0x2C*N,"meshArr pos");
        int arrEnd=m.meshesPtr+0x38*M;
        // bones: tree at arrEnd (root stub when 0 bones), bones array 0x64 each, then modelMeta
        int bonesArr=m.ptr(m.modelPtr+0x70), nB=le32(m.modelPtr+0x74);
        int bonesTree=m.ptr(m.modelPtr+0x78);
        chk(bonesTree==arrEnd,"bonesTree pos");
        chk(bonesArr==arrEnd+0xC*(nB+1),"bonesArr pos");
        int afterBones=bonesArr+0x64*nB;
        if (m.modelMetaPtr!=0) chk(m.modelMetaPtr==afterBones,"modelMeta pos");
        // layer boundaries: 48<=4C==50<=54==58<=5C==60<=64==78, and meshes sorted by layer within
        int b0=m.ptr(m.modelPtr+0x48), e0=m.ptr(m.modelPtr+0x4C), b1=m.ptr(m.modelPtr+0x50), e1=m.ptr(m.modelPtr+0x54);
        int b2=m.ptr(m.modelPtr+0x58), e2=m.ptr(m.modelPtr+0x5C), b3=m.ptr(m.modelPtr+0x60), e3=m.ptr(m.modelPtr+0x64);
        chk(b0==m.meshesPtr&&e0==b1&&e1==b2&&e2==b3&&e3==arrEnd,"layer chain");
        for (int j=0;j<M;j++){
            int h=m.meshes.get(j)[0];
            int lam=le32(h+4)>>>24;
            int lo=lam==0?b0:lam==1?b1:lam==2?b2:b3;
            int hi=lam==0?e0:lam==1?e1:lam==2?e2:e3;
            chk(h>=lo&&h<hi,"mesh"+j+" in layer segment "+lam);
        }
        // model meta (nullable)
        int mmv=0, mmc=0;
        int cur;
        if (m.modelMetaPtr!=0){
            mmv=m.ptr(m.modelMetaPtr); mmc=le32(m.modelMetaPtr+4);
            int mmt=m.ptr(m.modelMetaPtr+8);
            chk(mmt==m.modelMetaPtr+0xC&&mmv==mmt+0xC*(mmc+1),"modelMeta dict");
            cur=mmv+0xC*mmc;
        } else cur=afterBones;
        // material blocks in order
        for (int i=0;i<N;i++){
            int pp=m.materialParamOffsets.get(i);
            chk(pp==cur,"mat"+i+" params pos");
            int h=m.matValuesPtr+i*0x2C;
            chk(m.ptr(h+0x18)==pp+0x110,"mat"+i+" objA");
            int meta=m.ptr(pp+0x10C);
            chk(meta==pp+0x140,"mat"+i+" meta pos");
            int v=m.ptr(meta), c2=le32(meta+4), t2=m.ptr(meta+8);
            chk(t2==meta+0xC&&v==t2+0xC*(c2+1),"mat"+i+" meta dict");
            int end=v+0xC*c2;
            for (int k=0;k<c2;k++){ int dp=m.ptr(v+k*0xC+8); if (dp!=0) end=Math.max(end,dp); }
            // cannot know payload sizes; next object bound check:
            cur=pp; // replaced below by next-start logic
            cur=pp; // placeholder
            int next=(i+1<N)?m.materialParamOffsets.get(i+1):minSub();
            chk(end<next,"mat"+i+" extent");
            cur=next;
        }
        // mesh blocks contiguous in ADDRESS order
        List<int[]> subs=new ArrayList<>();
        for (int j=0;j<M;j++) subs.add(new int[]{m.meshes.get(j)[3], j});
        subs.sort((a,b)->Integer.compare(a[0],b[0]));
        chk(subs.get(0)[0]==cur,"first submesh pos");
        int tail=C+m.contentsLen;
        if (m.modelMetaPtr!=0) for (int k=0;k<mmc;k++){ int dp=m.ptr(mmv+k*0xC+8); if (dp!=0) tail=Math.min(tail,dp); }
        for (int k=0;k<subs.size();k++){
            int sp=subs.get(k)[0], j=subs.get(k)[1];
            int next=(k+1<subs.size())?subs.get(k+1)[0]:tail;
            int meta=m.ptr(m.meshes.get(j)[0]+0x34);
            chk(meta==sp+0x34,"mesh"+j+" meta pos");
            int v=m.ptr(meta), c2=le32(meta+4), t2=m.ptr(meta+8);
            chk(t2==meta+0xC&&v==t2+0xC*(c2+1),"mesh"+j+" meta dict");
            for (int q=0;q<c2;q++){ int dp=m.ptr(v+q*0xC+8); chk(dp==0||(dp>=sp&&dp<next),"mesh"+j+" payload local"); }
        }
        // commands coverage by address order
        List<int[]> streams=new ArrayList<>(); // {start,len}
        for (int i=0;i<N;i++){
            int pp=m.materialParamOffsets.get(i);
            int frag=m.ptr(pp+0xC8);
            int h=m.matValuesPtr+i*0x2C;
            int tex=m.ptr(h+0x10);
            chk(le32(pp+0xD0)==frag-m.commandsAddr,"mat"+i+" f03==frag");
            streams.add(new int[]{frag, tex+4*le32(h+0x14)-frag});
        }
        for (int j=0;j<M;j++){
            int[] me=m.meshes.get(j);
            int sub=m.ptr(me[3]+0x2C), dis=m.ptr(me[0]+0x18);
            chk(sub==me[1]+4*me[2],"mesh"+j+" sub follows enable");
            chk(dis==sub+4*le32(me[3]+0x30),"mesh"+j+" dis follows sub");
            streams.add(new int[]{me[1], dis+4*le32(me[0]+0x1C)-me[1]});
        }
        streams.sort((a,b)->Integer.compare(a[0],b[0]));
        // aux(LUT) regions carry LUT data at the start of commands; require contiguity from the first stream on
        int cp=streams.get(0)[0];
        chk(m.auxDicts.isEmpty() ? cp==m.commandsAddr : cp>=m.commandsAddr, "cmd start");
        for (int[] s : streams){ chk(s[0]==cp,"cmd gap at "+Integer.toHexString(cp)); cp=s[0]+s[1]; }
        chk(cp==m.commandsAddr+m.commandsLen,"cmd end");
        // rawdata: vtx group, idx16 group, idx8 group, packed from 0
        List<int[]> bufs=new ArrayList<>();
        for (int[] vb : m.vtxBuffers) bufs.add(new int[]{vb[0],0});
        for (int[] ib : m.idxBuffers) bufs.add(new int[]{ib[0],ib[2]==2?1:2});
        bufs.sort((a,b)->Integer.compare(a[0],b[0]));
        chk(bufs.get(0)[0]==m.rawDataAddr,"raw start");
        int kind=0;
        for (int[] b : bufs){ chk(b[1]>=kind,"raw group order"); kind=Math.max(kind,b[1]); }
        // reloc: 3M CMD entries first, grouped per stream-order trio
        int i2=0;
        for (; i2<m.reloc.length; i2++){ if (((m.reloc[i2]>>>25)&0x7F)>>>4!=2) break; }
        chk(i2==3*M,"cmd reloc block size");
        for (int j2=0;j2<M;j2++){
            int f0=(m.reloc[j2*3]>>>25)&0x7F, f1=(m.reloc[j2*3+1]>>>25)&0x7F, f2=(m.reloc[j2*3+2]>>>25)&0x7F;
            chk(f0==0x2E&&f1==0x26&&(f2==0x27||f2==0x28),"trio "+j2);
        }
        return fails;
    }
    static int minSub(){ int v=Integer.MAX_VALUE; for (int j=0;j<m.meshCount;j++) v=Math.min(v,m.meshes.get(j)[3]); return v; }
}
