package azkaban.common.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.avro.Schema;
import org.apache.avro.file.DataFileStream;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.Encoder;
import org.apache.avro.io.JsonEncoder;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.log4j.Logger;
import org.codehaus.jackson.JsonEncoding;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;

/**
 * This class implements a viewer of avro files
 * 
 * @author lguo
 * 
 */
public class HdfsAvroFileViewer implements HdfsFileViewer {

    private static Logger logger = Logger.getLogger(HdfsAvroFileViewer.class);

    @Override
    public boolean canReadFile(FileSystem fs, Path path) {

        if(logger.isDebugEnabled())
            logger.debug("path:" + path.toUri().getPath());

        try {
            DataFileStream<Object> avroDataStream = getAvroDataStream(fs, path);
            Schema schema = avroDataStream.getSchema();
            avroDataStream.close();
            return schema != null;
        } catch(IOException e) {
            if(logger.isDebugEnabled()) {
                logger.debug(path.toUri().getPath() + " is not an avro file.");
                logger.debug("Error in getting avro schema: " + e.getLocalizedMessage());
            }
            return false;
        }
    }

    private DataFileStream<Object> getAvroDataStream(FileSystem fs, Path path) throws IOException {
        if(logger.isDebugEnabled())
            logger.debug("path:" + path.toUri().getPath());

        GenericDatumReader<Object> avroReader = new GenericDatumReader<Object>();
        InputStream hdfsInputStream = fs.open(path);
        return new DataFileStream<Object>(hdfsInputStream, avroReader);

    }

    @Override
    public void displayFile(FileSystem fs,
                            Path path,
                            OutputStream outputStream,
                            int startLine,
                            int endLine) throws IOException {

        if(logger.isDebugEnabled())
            logger.debug("display avro file:" + path.toUri().getPath());

        DataFileStream<Object> avroDatastream = null;

        try {
            avroDatastream = getAvroDataStream(fs, path);
            Schema schema = avroDatastream.getSchema();
            DatumWriter<Object> avroWriter = new GenericDatumWriter<Object>(schema);

            JsonGenerator g = new JsonFactory().createJsonGenerator(outputStream, JsonEncoding.UTF8);
            g.useDefaultPrettyPrinter();
            Encoder encoder = new JsonEncoder(schema, g);

            int lineno = 1; // line number starts from 1
            while(avroDatastream.hasNext() && lineno <= endLine) {
                Object datum = avroDatastream.next();
                if(lineno >= startLine) {
                    String record = "\n\n Record " + lineno + ":\n";
                    outputStream.write(record.getBytes("UTF-8"));
                    avroWriter.write(datum, encoder);
                    encoder.flush();
                }
                lineno++;
            }
        } catch(IOException e) {
            outputStream.write(("Error in display avro file: " + e.getLocalizedMessage()).getBytes("UTF-8"));
            throw e;
        } finally {
            avroDatastream.close();
        }
    }

}
