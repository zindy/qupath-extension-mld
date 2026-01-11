package qupath.ext.mld;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.common.GeneralTools;
import qupath.lib.geom.Point2;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.openslide.jna.OpenSlide;
import qupath.lib.images.servers.openslide.jna.OpenSlideLoader;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClassFactory;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * Class to support reading Visiopharm MLD annotation files in QuPath.
 * Adapted from Visiopharm-to-QuPath migration scripts.
 */
public class MldTools {

    private static final Logger logger = LoggerFactory.getLogger(MldTools.class);

    // MLD Shape Constants
    private static final int POLYGON = 0;
    private static final int ELLIPSE = 1;
    private static final int CIRCLE = 2;
    private static final int RECTANGLE = 5;
    private static final int SQUARE = 6;

    /**
     * Read MLD file and add annotations to the current image
     *
     * @param imageData The image data to add annotations to
     * @param mldFile The MLD file to import
     * @return true if successful
     */
    public static boolean readMLD(ImageData<?> imageData, File mldFile) {
        ImageServer<?> server = imageData.getServer();
        if (server == null) return false;

        if (!mldFile.exists()) {
            logger.error("MLD file does not exist: {}", mldFile.getAbsolutePath());
            return false;
        }

        logger.info("Reading MLD file: {}", mldFile.getAbsolutePath());

        var hierarchy = imageData.getHierarchy();
        List<PathObject> newObjects = new ArrayList<>();

        try {
            // Parse the MLD Binary
            MldData mldData = readMldBinary(mldFile);
            newObjects = convertMldToPathObjects(mldData, server);

            hierarchy.addObjects(newObjects);
            
            // Resolve hierarchy to establish parent-child relationships
            hierarchy.resolveHierarchy();
            
            return true;

        } catch (Exception e) {
            logger.error("Failed to read MLD file", e);
            return false;
        }
    }

    /**
     * Convert MldData to PathObjects without adding them to the hierarchy
     * Useful for custom processing before adding to image
     *
     * @param mldData The parsed MLD data
     * @param server The image server for coordinate transformation
     * @return List of PathObjects ready to be added to hierarchy
     */
    public static List<PathObject> convertMldToPathObjects(MldData mldData, ImageServer<?> server) {
        // 3. Setup Coordinate Transformer
        VisiopharmTransformer transformer;
        if (mldData.imageInfoXml != null && !mldData.imageInfoXml.isEmpty()) {
            logger.info("Using MLD embedded ImageInfo for coordinates.");
            transformer = new VisiopharmTransformer(mldData.imageInfoXml, server);
        } else {
            logger.info("Using Server Metadata (Hamamatsu offsets) for coordinates.");
            transformer = new VisiopharmTransformer(server);
        }

        // 4. Parse Class Names
        Map<String, Map<Integer, String>> classMap = parseLayerConfigs(mldData.layerConfigsXml);

        // 5. Convert and Add Objects
        List<PathObject> newObjects = new ArrayList<>();

        for (MldLayer layer : mldData.layers) {
            boolean isDetectionLayer = layer.name.equalsIgnoreCase("Label");
            List<PathAnnotationObject> layerSolids = new ArrayList<>();
            List<ROI> layerHoles = new ArrayList<>();

            for (MldObject obj : layer.objects) {
                List<Point2> points = new ArrayList<>();
                for (Point2 raw : obj.getPoints()) {
                    points.add(transformer.transform(raw.getX(), raw.getY()));
                }

                // Skip objects with insufficient points
                if (points.isEmpty()) {
                    logger.debug("Skipping object with no points");
                    continue;
                }
                
                if (points.size() < 3 && obj.shapeType == POLYGON) {
                    logger.debug("Skipping polygon with fewer than 3 points");
                    continue;
                }

                ROI roi = ROIs.createPolygonROI(points, null);
                
                // Handle Holes
                if (obj.type == 0) {
                    layerHoles.add(roi);
                    continue;
                }

                // Create PathObject (Fix: Use constructors instead of factory methods)
                PathObject pathObj;
                if (isDetectionLayer) {
                    pathObj = PathObjects.createDetectionObject(roi);
                } else {
                    pathObj = PathObjects.createAnnotationObject(roi);
                }

                if (obj.text != null && !obj.text.isEmpty()) pathObj.setName(obj.text);
                
                String className = classMap.getOrDefault(layer.name, new HashMap<>())
                        .getOrDefault(obj.type, "Type " + obj.type);
                
                if (className != null && !className.isEmpty()) {
                    pathObj.setPathClass(PathClassFactory.getPathClass(className));
                }

                if (pathObj instanceof PathAnnotationObject) {
                    layerSolids.add((PathAnnotationObject) pathObj);
                } else {
                    newObjects.add(pathObj);
                }
            }

            // Geometry Subtraction
            if (!layerHoles.isEmpty() && !layerSolids.isEmpty()) {
                for (ROI holeRoi : layerHoles) {
                    Geometry holeGeom = holeRoi.getGeometry();
                    for (PathAnnotationObject parent : layerSolids) {
                        Geometry parentGeom = parent.getROI().getGeometry();
                        if (parentGeom.covers(holeGeom)) {
                            try {
                                Geometry diff = parentGeom.difference(holeGeom);
                                parent.setROI(GeometryTools.geometryToROI(diff, parent.getROI().getImagePlane()));
                                break; 
                            } catch (Exception e) {
                                logger.warn("Error subtracting hole", e);
                            }
                        }
                    }
                }
            }
            newObjects.addAll(layerSolids);
        }

        return newObjects;
    }
    
    /**
     * Read MLD file and add annotations to the current image (auto-locate version)
     * This method tries to find the MLD file automatically based on naming conventions
     *
     * @param imageData The image data to add annotations to
     * @return true if successful
     */
    public static boolean readMLD(ImageData<?> imageData) {
        ImageServer<?> server = imageData.getServer();
        if (server == null) return false;

        // 1. Locate the MLD file
        String imgPath = GeneralTools.toPath(server.getURIs().iterator().next()).toString();
        File mldFile = new File(imgPath + ".mld");
        
        if (!mldFile.exists()) {
             // Check generic "LayerData.mld" in the same folder
             File parent = new File(imgPath).getParentFile();
             if (parent != null) mldFile = new File(parent, "LayerData.mld");
        }
        
        if (!mldFile.exists()) {
            // Check _Result.mld
            String baseName = imgPath;
            if (baseName.lastIndexOf('.') > 0) baseName = baseName.substring(0, baseName.lastIndexOf('.'));
            mldFile = new File(baseName + "_Result.mld");
        }

        if (!mldFile.exists()) {
            logger.error("No MLD file found for this image: {}", imgPath);
            return false;
        }

        return readMLD(imageData, mldFile);
    }
    
    public static boolean writeMLD(ImageData<?> imageData) {
        logger.error("Export to MLD is not currently supported.");
        return false;
    }

    // ==========================================
    // Internal Helper Classes & Methods
    // ==========================================

    /**
     * Helper to read correct Hamamatsu offsets directly from OpenSlide properties.
     * This bypasses the cleaned metadata map to get the raw vendor tags.
     */
    private static double[] getOffsetUsingOpenSlide(ImageServer<?> server) {
        double centerX = server.getWidth() / 2.0;
        double centerY = server.getHeight() / 2.0;
        
        var cal = server.getPixelCalibration();
        if (!cal.hasPixelSizeMicrons()) return new double[]{centerX, centerY};

        double pixelWidthNm = cal.getPixelWidthMicrons() * 1000;
        double pixelHeightNm = cal.getPixelHeightMicrons() * 1000;

        URI uri = server.getURIs().iterator().next();
        Path path = GeneralTools.toPath(uri);
        if (path == null || !Files.exists(path))
            path = null;

        try (OpenSlide osr = path != null
                ? OpenSlideLoader.openImage(path.toRealPath().toString())
                : OpenSlideLoader.openImage(uri.toString())) {

            if (osr == null) {
                logger.warn("Could not open OpenSlide image: {}", uri);
                return new double[] { centerX, centerY };
            }

            Map<String, String> props = osr.getProperties();

            double xOffset = parseDoubleOrDefault(props.get("hamamatsu.XOffsetFromSlideCentre"));
            double yOffset = parseDoubleOrDefault(props.get("hamamatsu.YOffsetFromSlideCentre"));

            if (!Double.isNaN(xOffset))
                centerX -= xOffset / pixelWidthNm;
            if (!Double.isNaN(yOffset))
                centerY -= yOffset / pixelHeightNm;

        } catch (Exception e) {
            logger.error("Error reading offsets with OpenSlide", e);
        }

        return new double[] { centerX, centerY };
    }

    private static double parseDoubleOrDefault(String str) {
        try {
            return str != null ? Double.parseDouble(str) : Double.NaN;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    public static MldData readMldBinary(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        MldData data = new MldData();

        buf.getInt(); // magic
        buf.getInt(); // version
        int nLayers = buf.getInt();

        for (int i = 0; i < nLayers; i++) parseLayer(buf, data);

        while (buf.hasRemaining()) {
            String tag = readString(buf);
            if (tag.startsWith("[LayerConfigs")) {
                long len = buf.getLong();
                byte[] xml = new byte[(int) len]; buf.get(xml);
                data.layerConfigsXml = new String(xml, StandardCharsets.UTF_8);
            } else if (tag.startsWith("[ImageInfo")) {
                int start = buf.position();
                while (buf.hasRemaining() && buf.get() != (byte) '<') {}
                if (buf.hasRemaining()) {
                    buf.position(buf.position() - 1);
                    byte[] rest = new byte[buf.remaining()]; buf.get(rest);
                    String s = new String(rest, StandardCharsets.UTF_8);
                    if (s.contains("LDFF")) s = s.substring(0, s.lastIndexOf("LDFF"));

                    data.imageInfoXml = s;
                }
            }
        }
        return data;
    }

    private static void parseLayer(ByteBuffer buf, MldData data) {
        int startPos = buf.position();
        String name = "";
        int nobjects = 0;
        
        while (buf.hasRemaining()) {
            buf.mark();
            byte[] nb = new byte[64]; buf.get(nb);
            buf.get(); 
            nobjects = buf.getInt();
            name = new String(nb, StandardCharsets.UTF_8).trim();
            if (name.indexOf('\0') >= 0) name = name.substring(0, name.indexOf('\0'));
            
            if (name.equals("ROI") || name.equals("Label") || name.equals("Annotation")) break;
            
            buf.reset(); buf.get(); 
            if (buf.position() - startPos > 5000) return; 
        }

        MldLayer layer = new MldLayer();
        layer.name = name;
        data.layers.add(layer);

        if (nobjects > 0) {
            int size = buf.getInt();
            int endPos = buf.position() + size;
            for (int i = 0; i < nobjects && buf.position() < endPos; i++) {
                readObject(buf, layer);
            }
            buf.position(endPos);
        }
    }

    private static void readObject(ByteBuffer buf, MldLayer layer) {
        byte shape = buf.get();
        byte type = buf.get();
        MldObject obj = new MldObject();
        obj.shapeType = shape;
        obj.type = type;

        if (shape == POLYGON || shape == 8) readPoly(buf, obj);
        else if (shape == CIRCLE) readCircle(buf, obj);
        else if (shape == RECTANGLE) readRect(buf, obj);
        else if (shape == SQUARE) readSquare(buf, obj);
        else if (shape == ELLIPSE) readEllipse(buf, obj);
        else {
            logger.debug("Skipping unsupported shape type: {}", shape);
            return;
        }

        obj.text = readString(buf);
        obj.additional = readString(buf);
        
        // Only add objects that have valid points
        if (!obj.xPoints.isEmpty()) {
            layer.objects.add(obj);
        } else {
            logger.debug("Skipping object with no points after parsing");
        }
    }

    private static void readPoly(ByteBuffer buf, MldObject obj) {
        int n = buf.getInt();
        for (int i = 0; i < n; i++) obj.addPoint(buf.getFloat(), buf.getFloat());
    }

    private static void readCircle(ByteBuffer buf, MldObject obj) {
        buf.getInt(); double x = buf.getDouble(), y = buf.getDouble(), r = buf.getDouble();
        for (int i = 0; i <= 36; i++) {
            double a = (i / 36.0) * 2 * Math.PI;
            obj.addPoint(r * Math.cos(a) + x, r * Math.sin(a) + y);
        }
    }

    private static void readRect(ByteBuffer buf, MldObject obj) {
        buf.getInt(); double x = buf.getDouble(), y = buf.getDouble(), w = buf.getDouble(), h = buf.getDouble(), ang = buf.getDouble();
        double[] xo = {-w , w, w, -w, -w}; 
        double[] yo = {-h, -h, h, h, -h};
        for (int i = 0; i < 5; i++) {
            obj.addPoint(xo[i] * Math.cos(ang) - yo[i] * Math.sin(ang) + x,
                         xo[i] * Math.sin(ang) + yo[i] * Math.cos(ang) + y);
        }
    }
    
    private static void readSquare(ByteBuffer buf, MldObject obj) {
        buf.getInt(); 
        double x = buf.getDouble(), y = buf.getDouble(), w = buf.getDouble(), ang = buf.getDouble();
        double[] xo = {-w , w, w, -w, -w}; 
        double[] yo = {-w, -w, w, w, -w};
        for (int i = 0; i < 5; i++) {
            obj.addPoint(
                xo[i] * Math.cos(ang) - yo[i] * Math.sin(ang) + x,
                xo[i] * Math.sin(ang) + yo[i] * Math.cos(ang) + y
            );
        }
    }

    private static void readEllipse(ByteBuffer buf, MldObject obj) {
        buf.getInt(); double x = buf.getDouble(), y = buf.getDouble(), maj = buf.getDouble(), min = buf.getDouble(), ang = buf.getDouble();
        for(int i=0; i<=36; i++) {
            double a = (i/36.0)*2*Math.PI;
            double dx = maj*Math.cos(ang)*Math.cos(a) - min*Math.sin(ang)*Math.sin(a);
            double dy = maj*Math.sin(ang)*Math.cos(a) + min*Math.cos(ang)*Math.sin(a);
            obj.addPoint(dx + x, dy + y);
        }
        buf.getInt(); buf.getDouble(); buf.getDouble(); buf.getDouble(); 
    }

    private static String readString(ByteBuffer buf) {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        while (buf.hasRemaining()) { byte b = buf.get(); if (b == 0) break; baos.write(b); }
        return new String(baos.toByteArray(), StandardCharsets.US_ASCII);
    }
    
    private static Map<String, Map<Integer, String>> parseLayerConfigs(String xml) {
        Map<String, Map<Integer, String>> map = new HashMap<>();
        if (xml == null || xml.isEmpty()) return map;
        return map; 
    }

    //static class MldData { List<MldLayer> layers = new ArrayList<>(); String layerConfigsXml; String imageInfoXml; }
    public static class MldData { 
        public List<MldLayer> layers = new ArrayList<>();
        public String layerConfigsXml;
        public String imageInfoXml;
        
        /**
         * Get the ImageInfo XML if embedded in the MLD file
         * @return XML string or null if not present
         */
        public String getImageInfoXml() {
            return imageInfoXml;
        }
        
        /**
         * Get the LayerConfigs XML if embedded in the MLD file
         * @return XML string or null if not present
         */
        public String getLayerConfigsXml() {
            return layerConfigsXml;
        }
    }

    static class MldLayer { String name; List<MldObject> objects = new ArrayList<>(); }
    static class MldObject { 
        int shapeType, type; String text, additional; 
        List<Double> xPoints = new ArrayList<>(), yPoints = new ArrayList<>();
        void addPoint(double x, double y) { xPoints.add(x); yPoints.add(y); }
        List<Point2> getPoints() { 
            List<Point2> p = new ArrayList<>(); 
            for(int i=0;i<xPoints.size();i++) p.add(new Point2(xPoints.get(i), yPoints.get(i))); 
            return p;
        }
    }

    static class VisiopharmTransformer {
        double scaleX, scaleY, offsetX, offsetY;

        VisiopharmTransformer(String xml, ImageServer<?> server) {
            double left = extractXmlDouble(xml, "Left");
            double top = extractXmlDouble(xml, "Top");
            
            var cal = server.getPixelCalibration();
            double pxW = cal.getPixelWidthMicrons();
            double pxH = cal.getPixelHeightMicrons();
            
            this.scaleX = 1000.0 / pxW;
            this.scaleY = -1000.0 / pxH;
            this.offsetX = -left * scaleX;
            this.offsetY = -top * scaleY;
        }

        VisiopharmTransformer(ImageServer<?> server) {
            var cal = server.getPixelCalibration();
            double pxW = cal.getPixelWidthMicrons();
            if (Double.isNaN(pxW)) pxW = 0.5; 
            double pxH = cal.getPixelHeightMicrons();
            if (Double.isNaN(pxH)) pxH = 0.5;

            this.scaleX = 1000.0 / pxW;
            this.scaleY = -1000.0 / pxH;
            
            // Fix: Use the new helper method to get correct offsets
            double[] offsets = getOffsetUsingOpenSlide(server);
            this.offsetX = offsets[0];
            this.offsetY = offsets[1];
        }

        Point2 transform(double x, double y) {
            return new Point2(x * scaleX + offsetX, y * scaleY + offsetY);
        }
    }
        
    /**
     * Extract image path from MLD ImageInfo XML
     *
     * @param mldData The parsed MLD data
     * @return Image path string, or null if not found
     */
    public static String getImagePathFromMld(MldData mldData) {
        if (mldData.imageInfoXml == null || mldData.imageInfoXml.isEmpty()) {
            return null;
        }
        
        try {
            return extractXmlValue(mldData.imageInfoXml, "ImagePath");
        } catch (Exception e) {
            logger.warn("Failed to extract ImagePath from MLD", e);
            return null;
        }
    }

    /**
     * Extract a value from simple XML structure
     * Helper method for parsing MLD embedded XML
     *
     * @param xml The XML string
     * @param tag The tag name to extract
     * @return The value as a string, or null if not found
     */
    public static String extractXmlValue(String xml, String tag) {
        try {
            String s = xml.split("<" + tag + ">")[1].split("</" + tag + ">")[0];
            return s.trim();
        } catch(Exception e) { 
            return null;
        }
    }

    /**
     * Extract a numeric value from simple XML structure
     *
     * @param xml The XML string
     * @param tag The tag name to extract
     * @return The value as a double, or 0.0 if not found
     */
    public static double extractXmlDouble(String xml, String tag) {
        try {
            String s = extractXmlValue(xml, tag);
            return s != null ? Double.parseDouble(s) : 0.0;
        } catch(Exception e) { 
            return 0.0;
        }
    }
}