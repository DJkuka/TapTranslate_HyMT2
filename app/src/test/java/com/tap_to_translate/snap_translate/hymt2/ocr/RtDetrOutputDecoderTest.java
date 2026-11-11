package com.tap_to_translate.snap_translate.hymt2.ocr;
import android.graphics.Rect;import java.util.List;import org.junit.Test;import static org.junit.Assert.*;
public class RtDetrOutputDecoderTest {
 @Test public void routesOnlyTextLabels(){
  List<RtDetrOutputDecoder.Detection> d=RtDetrOutputDecoder.decode(new RtDetrInferenceOutput(new long[]{0,1,2},new float[][]{{0,0,99,99},{10,10,40,30},{50,50,90,80}},new float[]{.99f,.9f,.8f}),100,100,.3f);
  assertEquals(2,d.size());assertEquals(OcrRegionKind.SPEECH_BUBBLE,d.get(0).kind);assertEquals(new Rect(10,10,40,30),d.get(0).bounds);assertEquals(OcrRegionKind.FREE_TEXT,d.get(1).kind);
 }
 @Test public void clipsAndRejectsInvalidBoxes(){
  List<RtDetrOutputDecoder.Detection>d=RtDetrOutputDecoder.decode(new RtDetrInferenceOutput(new long[]{2,2,2},new float[][]{{-4,-5,30,40},{Float.NaN,0,20,20},{5,5,7,7}},new float[]{.9f,.99f,.99f}),100,100,.3f);
  assertEquals(1,d.size());assertEquals(new Rect(0,0,30,40),d.get(0).bounds);
 }
}