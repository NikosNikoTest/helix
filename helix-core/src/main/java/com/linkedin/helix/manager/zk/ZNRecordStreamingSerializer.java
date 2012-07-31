package com.linkedin.helix.manager.zk;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.I0Itec.zkclient.exception.ZkMarshallingError;
import org.I0Itec.zkclient.serialize.ZkSerializer;
import org.apache.log4j.Logger;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonToken;

import com.linkedin.helix.HelixException;
import com.linkedin.helix.ZNRecord;

public class ZNRecordStreamingSerializer implements ZkSerializer
{
  private static Logger LOG = Logger.getLogger(ZNRecordStreamingSerializer.class);

  private static int getListFieldBound(ZNRecord record)
  {
    int max = Integer.MAX_VALUE;
    if (record.getSimpleFields().containsKey(ZNRecord.LIST_FIELD_BOUND))
    {
      String maxStr = record.getSimpleField(ZNRecord.LIST_FIELD_BOUND);
      try
      {
        max = Integer.parseInt(maxStr);
      }
      catch (Exception e)
      {
        LOG.error("IllegalNumberFormat for list field bound: " + maxStr);
      }
    }
    return max;
  }
  
  @Override
  public byte[] serialize(Object data) throws ZkMarshallingError
  {
    if (!(data instanceof ZNRecord))
    {
      // null is NOT an instance of any class
      LOG.error("Input object must be of type ZNRecord but it is " + data + ". Will not write to zk");
      throw new HelixException("Input object is not of type ZNRecord (was " + data + ")");
    }

    // apply retention policy on list field
    ZNRecord record = (ZNRecord) data;
    int max = getListFieldBound(record);
    if (max < Integer.MAX_VALUE)
    {
      Map<String, List<String>> listMap = record.getListFields();
      for (String key : listMap.keySet())
      {
        List<String> list = listMap.get(key);
        if (list.size() > max)
        {
          listMap.put(key, list.subList(0, max));
        }
      }
    }
    
    StringWriter sw = new StringWriter();
    try
    {
      JsonFactory f = new JsonFactory();
      JsonGenerator g = f.createJsonGenerator(sw);

      g.writeStartObject();

      // write id field
      g.writeRaw("\n  ");
      g.writeStringField("id", record.getId());

      // write simepleFields
      g.writeRaw("\n  ");
      g.writeObjectFieldStart("simpleFields");
      for (String key : record.getSimpleFields().keySet())
      {
        g.writeRaw("\n    ");
        g.writeStringField(key, record.getSimpleField(key));
      }
      g.writeRaw("\n  ");
      g.writeEndObject(); // for simpleFields

      // write listFields
      g.writeRaw("\n  ");
      g.writeObjectFieldStart("listFields");
      for (String key : record.getListFields().keySet())
      {
        // g.writeStringField(key, record.getListField(key).toString());

        // g.writeObjectFieldStart(key);
        g.writeRaw("\n    ");
        g.writeArrayFieldStart(key);
        List<String> list = record.getListField(key);
        for (String listValue : list)
        {
          g.writeString(listValue);
        }
        // g.writeEndObject();
        g.writeEndArray();

      }
      g.writeRaw("\n  ");
      g.writeEndObject(); // for listFields

      // write mapFields
      g.writeRaw("\n  ");
      g.writeObjectFieldStart("mapFields");
      for (String key : record.getMapFields().keySet())
      {
        // g.writeStringField(key, record.getMapField(key).toString());
        g.writeRaw("\n    ");
        g.writeObjectFieldStart(key);
        Map<String, String> map = record.getMapField(key);
        for (String mapKey : map.keySet())
        {
          g.writeRaw("\n      ");
          g.writeStringField(mapKey, map.get(mapKey));
        }
        g.writeRaw("\n    ");
        g.writeEndObject();

      }
      g.writeRaw("\n  ");
      g.writeEndObject(); // for mapFields

      g.writeRaw("\n");
      g.writeEndObject(); // for whole znrecord

      // important: will force flushing of output, close underlying output
      // stream
      g.close();
    }
    catch (Exception e)
    {
      LOG.error("Exception during data serialization. Will not write to zk. Data (first 1k): "
          + sw.toString().substring(0, 1024), e);
      throw new HelixException(e);
    }
    
    // check size
    if (sw.toString().getBytes().length > ZNRecord.SIZE_LIMIT)
    {
      LOG.error("Data size larger than 1M, ZNRecord.id: " + record.getId() 
          + ". Will not write to zk. Data (first 1k): " + sw.toString().substring(0, 1024));
      throw new HelixException("Data size larger than 1M, ZNRecord.id: " + record.getId());
    }
    
    return sw.toString().getBytes();
  }

  @Override
  public Object deserialize(byte[] bytes) throws ZkMarshallingError
  {
    if (bytes == null || bytes.length == 0)
    {
      LOG.error("ZNode is empty.");
      return null;
    }
    
    ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
    ZNRecord record = null;

    try
    {
      JsonFactory f = new JsonFactory();
      JsonParser jp = f.createJsonParser(bais);

      jp.nextToken(); // will return JsonToken.START_OBJECT (verify?)
      while (jp.nextToken() != JsonToken.END_OBJECT)
      {
        String fieldname = jp.getCurrentName();
        jp.nextToken(); // move to value, or START_OBJECT/START_ARRAY
        if ("id".equals(fieldname))
        { 
          // contains an object
          record = new ZNRecord(jp.getText());
        }
        else if ("simpleFields".equals(fieldname))
        {
          while (jp.nextToken() != JsonToken.END_OBJECT)
          {
            String key = jp.getCurrentName();
            jp.nextToken(); // move to value
            record.setSimpleField(key, jp.getText());
          }
        }
        else if ("mapFields".equals(fieldname))
        {
          // user.setVerified(jp.getCurrentToken() == JsonToken.VALUE_TRUE);
          while (jp.nextToken() != JsonToken.END_OBJECT)
          {
            String key = jp.getCurrentName();
            record.setMapField(key, new TreeMap<String, String>());
            jp.nextToken(); // move to value

            while (jp.nextToken() != JsonToken.END_OBJECT)
            {
              String mapKey = jp.getCurrentName();
              jp.nextToken(); // move to value
              record.getMapField(key).put(mapKey, jp.getText());
            }
          }

        }
        else if ("listFields".equals(fieldname))
        {
          // user.setUserImage(jp.getBinaryValue());
          while (jp.nextToken() != JsonToken.END_OBJECT)
          {
            String key = jp.getCurrentName();
            record.setListField(key, new ArrayList<String>());
            jp.nextToken(); // move to value
            while (jp.nextToken() != JsonToken.END_ARRAY)
            {
              record.getListField(key).add(jp.getText());
            }

          }

        }
        else
        {
          throw new IllegalStateException("Unrecognized field '" + fieldname + "'!");
        }
      }
      jp.close(); // ensure resources get cleaned up timely and properly
    }
    catch (Exception e)
    {
      LOG.error("Exception during deserialization of bytes: " + new String(bytes), e);
    }

    return record;
  }

  public static void main(String[] args)
  {
    ZNRecord record = new ZNRecord("record");
    final int recordSize = 10;
    for (int i = 0; i < recordSize; i++)
    {
      record.setSimpleField("" + i, "" + i);
      record.setListField("" + i, new ArrayList<String>());
      for (int j = 0; j < recordSize; j++)
      {
        record.getListField("" + i).add("" + j);
      }

      record.setMapField("" + i, new TreeMap<String, String>());
      for (int j = 0; j < recordSize; j++)
      {
        record.getMapField("" + i).put("" + j, "" + j);
      }
    }

    ZNRecordStreamingSerializer serializer = new ZNRecordStreamingSerializer();
    byte[] bytes = serializer.serialize(record);
    System.out.println(new String(bytes));
    ZNRecord record2 = (ZNRecord) serializer.deserialize(bytes);
    System.out.println(record2);

    long start = System.currentTimeMillis();
    for (int i = 0; i < 100; i++)
    {
      bytes = serializer.serialize(record);
      // System.out.println(new String(bytes));
      record2 = (ZNRecord) serializer.deserialize(bytes);
      // System.out.println(record2);
    }
    long end = System.currentTimeMillis();
    System.out.println("ZNRecordStreamingSerializer time used: " + (end - start));

    ZNRecordSerializer serializer2 = new ZNRecordSerializer();
    bytes = serializer2.serialize(record);
    // System.out.println(new String(bytes));
    record2 = (ZNRecord) serializer2.deserialize(bytes);
    // System.out.println(record2);

    start = System.currentTimeMillis();
    for (int i = 0; i < 100; i++)
    {
      bytes = serializer2.serialize(record);
      // System.out.println(new String(bytes));
      record2 = (ZNRecord) serializer2.deserialize(bytes);
      // System.out.println(record2);
    }
    end = System.currentTimeMillis();
    System.out.println("ZNRecordSerializer time used: " + (end - start));

  }
}
