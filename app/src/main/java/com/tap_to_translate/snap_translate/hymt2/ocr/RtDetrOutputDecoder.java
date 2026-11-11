package com.tap_to_translate.snap_translate.hymt2.ocr;
import android.graphics.Rect;import java.util.*;
public final class RtDetrOutputDecoder {
 private RtDetrOutputDecoder(){}
 public static List<Detection> decode(RtDetrInferenceOutput o,int w,int h,float threshold){
  if(o==null||w<=0||h<=0||!Float.isFinite(threshold)||threshold<0||threshold>1)throw new IllegalArgumentException("Invalid RT-DETR input");
  List<Detection> out=new ArrayList<>();
  for(int i=0,n=Math.min(o.labels.length,Math.min(o.boxes.length,o.scores.length));i<n;i++){
   int label=(int)o.labels[i];OcrRegionKind kind=label==0?OcrRegionKind.BUBBLE_CONTAINER:label==1?OcrRegionKind.SPEECH_BUBBLE:label==2?OcrRegionKind.FREE_TEXT:null;
   if(kind==null||!Float.isFinite(o.scores[i])||o.scores[i]<threshold||o.boxes[i]==null||o.boxes[i].length<4)continue;
   float[] b=o.boxes[i];boolean valid=true;for(int k=0;k<4;k++)if(!Float.isFinite(b[k]))valid=false;if(!valid)continue;
   int l=Math.max(0,(int)Math.floor(b[0])),t=Math.max(0,(int)Math.floor(b[1])),r=Math.min(w,(int)Math.ceil(b[2])),bt=Math.min(h,(int)Math.ceil(b[3]));
   if(r-l<4||bt-t<4)continue;Rect rect=new Rect(l,t,r,bt);boolean dup=false;
   for(Detection d:out)if(d.kind==kind&&iou(rect,d.bounds)>=.90f){dup=true;break;}if(!dup)out.add(new Detection(kind,rect,o.scores[i],label));
  }out.sort((a,b)->Float.compare(b.confidence,a.confidence));return out;
 }
 private static float iou(Rect a,Rect b){Rect x=new Rect(a);if(!x.intersect(b))return 0;double inter=(double)x.width()*x.height(),u=(double)a.width()*a.height()+(double)b.width()*b.height()-inter;return u<=0?0:(float)(inter/u);}
 public static final class Detection {public final OcrRegionKind kind;public final Rect bounds;public final float confidence;public final int label;Detection(OcrRegionKind k,Rect b,float c,int l){kind=k;bounds=new Rect(b);confidence=c;label=l;}}
}