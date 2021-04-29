package com.dynamsoft.farawayscan;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.WorkerThread;

import com.dynamsoft.dbr.BarcodeReader;
import com.dynamsoft.dbr.BarcodeReaderException;
import com.dynamsoft.dbr.EnumConflictMode;
import com.dynamsoft.dbr.EnumIntermediateResultSavingMode;
import com.dynamsoft.dbr.EnumIntermediateResultType;
import com.dynamsoft.dbr.EnumResultCoordinateType;
import com.dynamsoft.dbr.EnumScaleUpMode;
import com.dynamsoft.dbr.IntermediateResult;
import com.dynamsoft.dbr.LocalizationResult;
import com.dynamsoft.dbr.Point;
import com.dynamsoft.dbr.PublicRuntimeSettings;

public class Utils {

    public static void updateDBRSettings(BarcodeReader dbr, SharedPreferences prefs) throws BarcodeReaderException {
        String template = prefs.getString("template","");
        if (template.trim()!=""){
            dbr.initRuntimeSettingsWithString(template, EnumConflictMode.CM_OVERWRITE);
        }

        String scaleup = prefs.getString("scaleup","");
        Boolean scaleupEnabled = false;
        if (scaleup.split(",").length==3) {
            scaleupEnabled=true;
        }

        PublicRuntimeSettings rs = dbr.getRuntimeSettings();
        rs.intermediateResultTypes= EnumIntermediateResultType.IRT_TYPED_BARCODE_ZONE;
        rs.intermediateResultSavingMode= EnumIntermediateResultSavingMode.IRSM_MEMORY;
        rs.resultCoordinateType= EnumResultCoordinateType.RCT_PIXEL;

        if (scaleupEnabled){
            rs.scaleUpModes[1] = Integer.parseInt(scaleup.split(",")[0]);
        }
        
        dbr.updateRuntimeSettings(rs);
        if (scaleupEnabled) {
            Log.d("DBR","scaleup enabled");
            dbr.setModeArgument("ScaleUpModes",1,"ModuleSizeThreshold",scaleup.split(",")[1]);
            dbr.setModeArgument("ScaleUpModes",1,"TargetModuleSize",scaleup.split(",")[2]);
        }
    }

    public static Point[] getResultsPointsWithHighestConfidence(IntermediateResult[] intermediateResults){
        for (IntermediateResult ir:intermediateResults){
            if (ir.resultType== EnumIntermediateResultType.IRT_TYPED_BARCODE_ZONE){
                int maxConfidence=0;
                for (Object result:ir.results)
                {
                    LocalizationResult lr = (LocalizationResult) result;
                    maxConfidence=Math.max(lr.confidence,maxConfidence);
                    Log.d("DBR", "confidence: "+lr.confidence);
                }
                Log.d("DBR", "max confidence: "+maxConfidence);
                for (Object result:ir.results)
                {
                    LocalizationResult lr = (LocalizationResult) result;
                    if (lr.confidence==maxConfidence && maxConfidence>80){
                        return lr.resultPoints;
                    }
                }
            }
        }
        return null;
    }

    public static Bitmap DetecetdBarcodeZone(Point[] resultPoints, Bitmap bitmap){
        int minX,maxX,minY,maxY;
        minX=resultPoints[0].x;
        minY=resultPoints[0].y;
        maxX=0;
        maxY=0;
        for (Point point:resultPoints){
            minX=Math.min(point.x,minX);
            minY=Math.min(point.y,minY);
            maxX=Math.max(point.x,maxX);
            maxY=Math.max(point.y,maxY);
        }
        int width = maxX-minX;
        int height = maxY-minY;
        if (width<0 || height<0){
            return bitmap;
        }
        Bitmap cropped = Bitmap.createBitmap(bitmap, minX, minY, width, height);
        return cropped;
    }
}
