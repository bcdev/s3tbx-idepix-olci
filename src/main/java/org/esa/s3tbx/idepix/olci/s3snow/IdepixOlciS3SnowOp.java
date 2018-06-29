package org.esa.s3tbx.idepix.olci.s3snow;

import org.esa.s3tbx.idepix.core.AlgorithmSelector;
import org.esa.s3tbx.idepix.core.IdepixConstants;
import org.esa.s3tbx.idepix.core.util.IdepixIO;
import org.esa.s3tbx.idepix.operators.BasisOp;
import org.esa.s3tbx.idepix.operators.IdepixProducts;
import org.esa.s3tbx.processor.rad2refl.Sensor;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.VirtualBand;
import org.esa.snap.core.gpf.GPF;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * The Idepix pixel classification operator for OLCI products.
 * Specific plugin version for S3-SNOW project.
 *
 * @author olafd
 */
@OperatorMetadata(alias = "Idepix.Sentinel3.Olci.S3Snow",
        category = "Optical/Pre-Processing",
        version = "0.82",
        authors = "Olaf Danne",
        copyright = "(c) 2018 by Brockmann Consult",
        description = "Pixel identification and classification for OLCI. Specific plugin version for S3-SNOW project.")
public class IdepixOlciS3SnowOp extends BasisOp {

    @SourceProduct(alias = "sourceProduct",
            label = "OLCI L1b product",
            description = "The OLCI L1b source product.")
    private Product sourceProduct;

    @SourceProduct(alias = "demProduct",
            optional = true,
            label = "DEM product for O2 correction",
            description = "DEM product.")
    private Product demProduct;

    @TargetProduct(description = "The target product.")
    private Product targetProduct;

    private boolean outputRadiance;
    private boolean outputRad2Refl;

    // we have many options from IDEPIX_DEVEL which are currently not needed for S3-SNOW, so they are disabled

    //    @Parameter(description = "The list of radiance bands to write to target product.",
//            label = "Select TOA radiances to write to the target product",
//            valueSet = {
//                    "Oa01_radiance", "Oa02_radiance", "Oa03_radiance", "Oa04_radiance", "Oa05_radiance",
//                    "Oa06_radiance", "Oa07_radiance", "Oa08_radiance", "Oa09_radiance", "Oa10_radiance",
//                    "Oa11_radiance", "Oa12_radiance", "Oa13_radiance", "Oa14_radiance", "Oa15_radiance",
//                    "Oa16_radiance", "Oa17_radiance", "Oa18_radiance", "Oa19_radiance", "Oa20_radiance",
//                    "Oa21_radiance"
//            },
//            defaultValue = "")
    private String[] radianceBandsToCopy;

//        @Parameter(description = "The list of reflectance bands to write to target product.",
//            label = "Select TOA reflectances to write to the target product",
//            valueSet = {
//                    "Oa01_reflectance", "Oa02_reflectance", "Oa03_reflectance", "Oa04_reflectance", "Oa05_reflectance",
//                    "Oa06_reflectance", "Oa07_reflectance", "Oa08_reflectance", "Oa09_reflectance", "Oa10_reflectance",
//                    "Oa11_reflectance", "Oa12_reflectance", "Oa13_reflectance", "Oa14_reflectance", "Oa15_reflectance",
//                    "Oa16_reflectance", "Oa17_reflectance", "Oa18_reflectance", "Oa19_reflectance", "Oa20_reflectance",
//                    "Oa21_reflectance"
//            },
//            defaultValue = "")
//    private String[] reflBandsToCopy;
    private String[] reflBandsToCopy = {"Oa21_reflectance"};  // needed for 'cloud over snow' band computation

    //    @Parameter(defaultValue = "false",
//            label = " Write NN value to the target product",
//            description = " If applied, write NN value to the target product ")
//    private boolean outputSchillerNNValue;
    private boolean outputSchillerNNValue = false;

    //    @Parameter(defaultValue = "true", label = " Compute a cloud buffer")
//    private boolean computeCloudBuffer;
    private boolean computeCloudBuffer = true;

    //    @Parameter(defaultValue = "false",
//            label = " Compute binary mask 'cloud_over_snow' using O2 corrected band13 transmission (experimental option)",
//            description = " Computes and writes a binary mask 'cloud_over_snow' using O2 corrected transmission at " +
//                    "band 13 (experimental option, requires additional plugin and reflectance band 21)")
//    private boolean applyO2CorrectedTransmission;
    private boolean applyO2CorrectedTransmission = true;

    @Parameter(defaultValue = "band_1",
            label = " Name of DEM band (if optional DEM product is provided)",
            description = "Name of DEM band in DEM product (if optionally provided)")
    private String demBandName;

    //    @Parameter(defaultValue = "2", interval = "[0,100]",
//            description = "The width of a cloud 'safety buffer' around a pixel which was classified as cloudy.",
//            label = "Width of cloud buffer (# of pixels)")
//    private int cloudBufferWidth;
    private int cloudBufferWidth = 2;

    private Product classificationProduct;
    private Product postProcessingProduct;

    private Product rad2reflProduct;

    private Map<String, Product> classificationInputProducts;
    private Map<String, Object> classificationParameters;
    private Product o2CorrProduct;

    @Override
    public void initialize() throws OperatorException {

        final boolean inputProductIsValid = IdepixIO.validateInputProduct(sourceProduct, AlgorithmSelector.OLCI);
        if (!inputProductIsValid) {
            throw new OperatorException(IdepixConstants.INPUT_INCONSISTENCY_ERROR_MESSAGE);
        }

        outputRadiance = radianceBandsToCopy != null && radianceBandsToCopy.length > 0;
        outputRad2Refl = reflBandsToCopy != null && reflBandsToCopy.length > 0;

        preProcess();

        setClassificationInputProducts();
        computeCloudProduct();

        Product olciIdepixProduct = classificationProduct;
        olciIdepixProduct.setName(sourceProduct.getName() + "_IDEPIX");
        olciIdepixProduct.setAutoGrouping("Oa*_radiance:Oa*_reflectance");

        ProductUtils.copyFlagBands(sourceProduct, olciIdepixProduct, true);

        if (computeCloudBuffer) {
            postProcess(olciIdepixProduct);
        }

        targetProduct = createTargetProduct(olciIdepixProduct);
        targetProduct.setAutoGrouping(olciIdepixProduct.getAutoGrouping());

        if (applyO2CorrectedTransmission) {
            ProductUtils.copyBand("trans_13", o2CorrProduct, targetProduct, true);
            ProductUtils.copyBand("press_13", o2CorrProduct, targetProduct, true);
            ProductUtils.copyBand("surface_13", o2CorrProduct, targetProduct, true);
            ProductUtils.copyBand("altitude", sourceProduct, targetProduct, true);
            addSurfacePressureBand();
            addCloudOverSnowBand();
        }

        if (postProcessingProduct != null) {
            Band cloudFlagBand = targetProduct.getBand(IdepixConstants.CLASSIF_BAND_NAME);
            cloudFlagBand.setSourceImage(postProcessingProduct.getBand(IdepixConstants.CLASSIF_BAND_NAME).getSourceImage());
        }
    }

    private Product createTargetProduct(Product idepixProduct) {
        Product targetProduct = new Product(idepixProduct.getName(),
                                            idepixProduct.getProductType(),
                                            idepixProduct.getSceneRasterWidth(),
                                            idepixProduct.getSceneRasterHeight());

        ProductUtils.copyMetadata(idepixProduct, targetProduct);
        ProductUtils.copyGeoCoding(idepixProduct, targetProduct);
        ProductUtils.copyFlagCodings(idepixProduct, targetProduct);
        ProductUtils.copyFlagBands(idepixProduct, targetProduct, true);
        ProductUtils.copyMasks(idepixProduct, targetProduct);
        ProductUtils.copyTiePointGrids(idepixProduct, targetProduct);
        targetProduct.setStartTime(idepixProduct.getStartTime());
        targetProduct.setEndTime(idepixProduct.getEndTime());

        IdepixOlciS3SnowUtils.setupOlciClassifBitmask(targetProduct);

        if (outputRadiance) {
            IdepixIO.addRadianceBands(sourceProduct, targetProduct, radianceBandsToCopy);
        }
        if (outputRad2Refl) {
            IdepixIO.addOlciRadiance2ReflectanceBands(rad2reflProduct, targetProduct, reflBandsToCopy);
        }

        if (outputSchillerNNValue) {
            ProductUtils.copyBand(IdepixConstants.NN_OUTPUT_BAND_NAME, idepixProduct, targetProduct, true);
        }

        return targetProduct;
    }


    private void preProcess() {
        rad2reflProduct = IdepixProducts.computeRadiance2ReflectanceProduct(sourceProduct, Sensor.OLCI);

        if (applyO2CorrectedTransmission) {
            Map<String, Product> o2corrSourceProducts = new HashMap<>();
            Map<String, Object> o2corrParms = new HashMap<>();
            o2corrSourceProducts.put("l1bProduct", sourceProduct);
            if (demProduct != null) {
                o2corrSourceProducts.put("DEM", demProduct);
                o2corrParms.put("demAltitudeBandName", demBandName);
            }
            final String o2CorrOpName = "O2CorrOlci";
            o2CorrProduct = GPF.createProduct(o2CorrOpName, o2corrParms, o2corrSourceProducts);
        }

    }

    private void setClassificationParameters() {
        classificationParameters = new HashMap<>();
        classificationParameters.put("copyAllTiePoints", true);
        classificationParameters.put("outputSchillerNNValue", outputSchillerNNValue);
    }

    private void computeCloudProduct() {
        setClassificationParameters();
        classificationProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(IdepixOlciS3SnowClassificationOp.class),
                                                  classificationParameters, classificationInputProducts);
    }

    private void setClassificationInputProducts() {
        classificationInputProducts = new HashMap<>();
        classificationInputProducts.put("l1b", sourceProduct);
        classificationInputProducts.put("rhotoa", rad2reflProduct);
    }

    private void postProcess(Product olciIdepixProduct) {
        HashMap<String, Product> input = new HashMap<>();
        input.put("l1b", sourceProduct);
        input.put("olciCloud", olciIdepixProduct);

        Map<String, Object> params = new HashMap<>();
        params.put("computeCloudBuffer", computeCloudBuffer);

        postProcessingProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(IdepixOlciS3SnowPostProcessOp.class),
                                                  params, input);
    }

    private void addSurfacePressureBand() {
        String presExpr = "(1013.25 * exp(-altitude/8400))";
        final Band surfPresBand = new VirtualBand("surface_pressure",
                                                  ProductData.TYPE_FLOAT32,
                                                  targetProduct.getSceneRasterWidth(),
                                                  targetProduct.getSceneRasterHeight(),
                                                  presExpr);
        surfPresBand.setDescription("estimated sea level pressure (p0=1013.25hPa, hScale=8.4km)");
        surfPresBand.setNoDataValue(0);
        surfPresBand.setNoDataValueUsed(true);
        surfPresBand.setUnit("hPa");
        targetProduct.addBand(surfPresBand);
    }

    private void addCloudOverSnowBand() {
        String expr = "pixel_classif_flags.IDEPIX_LAND && Oa21_reflectance > 0.5 && surface_13 - trans_13 < 0.01";
        final Band surfPresBand = new VirtualBand("cloud_over_snow",
                                                  ProductData.TYPE_FLOAT32,
                                                  targetProduct.getSceneRasterWidth(),
                                                  targetProduct.getSceneRasterHeight(),
                                                  expr);
        surfPresBand.setDescription("Pixel identified as likely cloud over a snow/ice surface");
        surfPresBand.setNoDataValue(0);
        surfPresBand.setNoDataValueUsed(true);
        surfPresBand.setUnit("dl");
        targetProduct.addBand(surfPresBand);
    }

    /**
     * The Service Provider Interface (SPI) for the operator.
     * It provides operator meta-data and is a factory for new operator instances.
     */
    public static class Spi extends OperatorSpi {

        public Spi() {
            super(IdepixOlciS3SnowOp.class);
        }
    }
}