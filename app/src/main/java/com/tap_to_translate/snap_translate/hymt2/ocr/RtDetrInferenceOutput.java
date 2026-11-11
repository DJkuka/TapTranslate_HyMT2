package com.tap_to_translate.snap_translate.hymt2.ocr;
public final class RtDetrInferenceOutput {
 public final long[] labels; public final float[][] boxes; public final float[] scores;
 public RtDetrInferenceOutput(long[] l,float[][] b,float[] s){if(l==null||b==null||s==null)throw new IllegalArgumentException("Missing RT-DETR output");labels=l.clone();boxes=new float[b.length][];for(int i=0;i<b.length;i++)boxes[i]=b[i]==null?null:b[i].clone();scores=s.clone();}
}