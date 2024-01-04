/*
 * (c) 1998-2016 University Corporation for Atmospheric Research/Unidata
 */

package thredds.server.ncss.view.dsg.station;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
import thredds.server.ncss.exception.NcssException;
import thredds.server.ncss.view.dsg.HttpHeaderWriter;
import ucar.ma2.Array;
import ucar.nc2.VariableSimpleIF;
import ucar.nc2.ft.*;
import ucar.nc2.ft.point.StationFeature;
import ucar.nc2.ft.point.StationPointFeature;
import ucar.nc2.ft.point.StationTimeSeriesFeatureImpl;
import ucar.nc2.ft.point.remote.PointStreamProto;
import ucar.nc2.ft2.coverage.SubsetParams;
import ucar.nc2.time.CalendarDate;
import ucar.nc2.time.CalendarDateFormatter;
import ucar.unidata.geoloc.Station;
import ucar.unidata.util.Format;

/**
 * Created by cwardgar on 2014-05-24.
 */
public class StationSubsetWriterCSV extends AbstractStationSubsetWriter {

  final protected PrintWriter writer;

  public StationSubsetWriterCSV(FeatureDatasetPoint fdPoint, SubsetParams ncssParams, OutputStream out)
      throws NcssException, IOException {
    this(fdPoint, ncssParams, out, 0);
  }

  public StationSubsetWriterCSV(FeatureDatasetPoint fdPoint, SubsetParams ncssParams, OutputStream out,
      int collectionIndex) throws NcssException, IOException {
    super(fdPoint, ncssParams, collectionIndex);
    this.writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
  }

  @Override
  public HttpHeaders getHttpHeaders(String datasetPath, boolean isStream) {
    return HttpHeaderWriter.getHttpHeadersForCSV(datasetPath, isStream);
  }

  @Override
  protected void writeHeader(StationPointFeature stationPointFeat) throws IOException {
    writer.print("time,station,latitude[unit=\"degrees_north\"],longitude[unit=\"degrees_east\"]");
    for (VariableSimpleIF wantedVar : wantedVariables) {
      if (stationPointFeat.getDataAll().getMembers().stream()
          .anyMatch(a -> a.getName().equals(wantedVar.getShortName()))) {
        writer.print(",");
        writer.print(wantedVar.getShortName());
        if (wantedVar.getUnitsString() != null)
          writer.print("[unit=\"" + wantedVar.getUnitsString() + "\"]");
      }
    }
    writer.println();
  }

  @Override
  public void write() throws Exception {
    int count = 0;

    for (StationFeatureCollection stationFeatureCol : this.stationFeatureCollectionCol) {
      headerDone = false;

      List<String> stnNames = wantedStations.stream().filter(x -> ((DsgFeatureCollection) x).getCollectionFeatureType() == stationFeatureCollection.getCollectionFeatureType()).map(y -> y.getName()).collect(Collectors.toList());
      // Perform spatial subset.
      List<StationFeature> subsettedStationFeatCol = stationFeatureCol.getStationFeatures(wantedStations.stream().map(x -> x.getName()).collect(Collectors.toList()));
//      StationTimeSeriesFeatureCollection subsettedStationFeatCol = ((StationTimeSeriesFeatureCollection) stationFeatureCol).subsetFeatures(wantedStations);

      for (StationFeature stationFeat : subsettedStationFeatCol) {

        // Perform temporal subset. We do this even when a time instant is specified, in which case wantedRange
        // represents a sanity check (i.e. "give me the feature closest to the specified time, but it must at
        // least be within an hour").
        StationProfileFeature subsettedStationFeat = ((StationProfileFeature) stationFeat).subset(wantedRange);

        if (ncssParams.getTime() != null) {
          CalendarDate wantedTime = ncssParams.getTime();
          subsettedStationFeat =
                  new ClosestTimeStationFeatureSubset((StationTimeSeriesFeatureImpl) subsettedStationFeat, wantedTime);
        }

        count += writeStationTimeSeriesFeature(subsettedStationFeat);
      }
    }
    if (count == 0) {
      throw new NcssException("No features are in the requested subset");
    }
    writeFooter();
  }

  @Override
  protected int writeStationTimeSeriesFeature(PointFeatureCollection stationFeat) throws Exception {
    int count = 0;
    //headerDone = false;
    for (PointFeature pointFeat : stationFeat) {
      assert pointFeat instanceof StationPointFeature : "Expected pointFeat to be a StationPointFeature, not a "
          + pointFeat.getClass().getSimpleName();
      if (!headerDone) {
        writeHeader((StationPointFeature) pointFeat);
        headerDone = true;
      }
      writeStationPointFeature((StationPointFeature) pointFeat);
      count++;
    }
    return count;
  }

  @Override
  protected void writeStationPointFeature(StationPointFeature stationPointFeat) throws IOException {
    Station station = stationPointFeat.getStation();

    writer.print(CalendarDateFormatter.toDateTimeStringISO(stationPointFeat.getObservationTimeAsCalendarDate()));
    writer.print(',');
    writer.print(station.getName());
    writer.print(',');
    writer.print(Format.dfrac(station.getLatitude(), 3));
    writer.print(',');
    writer.print(Format.dfrac(station.getLongitude(), 3));

    for (VariableSimpleIF wantedVar : wantedVariables) {
      if (stationPointFeat.getDataAll().getMembers().stream()
          .anyMatch(a -> a.getName().equals(wantedVar.getShortName()))) {
        writer.print(',');
        Array dataArray = stationPointFeat.getDataAll().getArray(wantedVar.getShortName());
        writer.print(dataArray.toString().trim());
      }
    }
    writer.println();
  }

  @Override
  protected void writeFooter() throws IOException {
    writer.flush();
  }
}
