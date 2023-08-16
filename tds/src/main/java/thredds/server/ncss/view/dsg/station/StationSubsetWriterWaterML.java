/*
 * Copyright (c) 1998-2018 John Caron and University Corporation for Atmospheric Research/Unidata
 * See LICENSE for license information.
 */
package thredds.server.ncss.view.dsg.station;

import net.opengis.waterml.x20.CollectionDocument;
import net.opengis.waterml.x20.CollectionType;
import org.springframework.http.HttpHeaders;
import thredds.server.ncss.exception.NcssException;
import thredds.server.ncss.view.dsg.HttpHeaderWriter;
import ucar.nc2.VariableSimpleIF;
import ucar.nc2.ft.FeatureDatasetPoint;
import ucar.nc2.ft.StationTimeSeriesFeature;
import ucar.nc2.ft.StationTimeSeriesFeatureCollection;
import ucar.nc2.ft.point.StationPointFeature;
import ucar.nc2.ft.point.StationTimeSeriesFeatureImpl;
import ucar.nc2.ft2.coverage.SubsetParams;
import ucar.nc2.ogc.MarshallingUtil;
import ucar.nc2.ogc.om.NcOMObservationPropertyType;
import ucar.nc2.ogc.waterml.NcDocumentMetadataPropertyType;
import ucar.nc2.time.CalendarDate;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Created by cwardgar on 2014/06/04.
 */
public class StationSubsetWriterWaterML extends AbstractStationSubsetWriter {
  private final OutputStream out;
  private final CollectionDocument collectionDoc;
  private final CollectionType collection;

  public StationSubsetWriterWaterML(FeatureDatasetPoint fdPoint, SubsetParams ncssParams, OutputStream out)
      throws NcssException, IOException {
    this(fdPoint, ncssParams, out, 0);
  }

  public StationSubsetWriterWaterML(FeatureDatasetPoint fdPoint, SubsetParams ncssParams, OutputStream out,
      int collectionIndex) throws NcssException, IOException {
    super(fdPoint, ncssParams, collectionIndex);

    this.out = out;
    this.collectionDoc = CollectionDocument.Factory.newInstance();
    this.collection = collectionDoc.addNewCollection();
  }

  @Override
  public HttpHeaders getHttpHeaders(String datasetPath, boolean isStream) {
    return HttpHeaderWriter.getHttpHeadersForXML(datasetPath, isStream);
  }

  @Override
  protected void writeHeader() {
    MarshallingUtil.resetIds();

    // @gml:id
    String id = MarshallingUtil.createIdForType(CollectionType.class);
    collection.setId(id);

    // wml2:metadata
    NcDocumentMetadataPropertyType.initMetadata(collection.addNewMetadata());
  }

  @Override
  protected int writeStationTimeSeriesFeature(StationTimeSeriesFeature stationFeat) throws Exception {
    if (!headerDone) {
      writeHeader();
      headerDone = true;
    }

    for (VariableSimpleIF wantedVar : wantedVariables) {
      // wml2:observationMember
      NcOMObservationPropertyType.initObservationMember(collection.addNewObservationMember(), stationFeat, wantedVar);
    }

    return 1; // ??
  }

  @Override
  protected int writeStationTimeSeriesFeatures(StationTimeSeriesFeatureCollection stationFeatCol) throws Exception {
    for (StationTimeSeriesFeature stationFeat : stationFeatCol) {
      StationTimeSeriesFeature subsettedStationFeat = stationFeat.subset(wantedRange);

      // Perform temporal subset. We do this even when a time instant is specified, in which case wantedRange
      // represents a sanity check (i.e. "give me the feature closest to the specified time, but it must at
      // least be within an hour").
      if (ncssParams.getTime() != null) {
        CalendarDate wantedTime = ncssParams.getTime();
        subsettedStationFeat =
            new ClosestTimeStationFeatureSubset((StationTimeSeriesFeatureImpl) subsettedStationFeat, wantedTime);
      }

      if (!headerDone) {
        writeHeader();
        headerDone = true;
      }

      for (VariableSimpleIF wantedVar : wantedVariables) {
        // wml2:observationMember
        NcOMObservationPropertyType.initObservationMember(collection.addNewObservationMember(), subsettedStationFeat,
            wantedVar);
      }
    }

    return 1; // ??
  }

  @Override
  protected void writeStationPointFeature(StationPointFeature stationPointFeat) throws Exception {
    throw new UnsupportedOperationException("Method not used in " + getClass());
  }

  @Override
  protected void writeFooter() throws Exception {
    MarshallingUtil.writeObject(collectionDoc, out, true);
    out.flush();
  }
}
