package org.tron.core.services.http.servlets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.tron.core.services.http.servlets.JsonFormatTestSupport.repeat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.tron.api.GrpcAPI.BytesMessage;
import org.tron.api.GrpcAPI.NumberMessage;
import org.tron.protos.contract.BalanceContract.FreezeBalanceV2Contract;
import org.tron.protos.contract.Common.ResourceCode;

public class JsonFormatFormInputTest {

  private static final Descriptor SCALARS = buildScalars();

  private static Descriptor buildScalars() {
    DescriptorProto.Builder message = DescriptorProto.newBuilder().setName("Scalars");
    Object[][] fields = {
        {"i32", Type.TYPE_INT32}, {"si32", Type.TYPE_SINT32}, {"sf32", Type.TYPE_SFIXED32},
        {"i64", Type.TYPE_INT64}, {"si64", Type.TYPE_SINT64}, {"sf64", Type.TYPE_SFIXED64},
        {"u32", Type.TYPE_UINT32}, {"f32", Type.TYPE_FIXED32},
        {"u64", Type.TYPE_UINT64}, {"f64", Type.TYPE_FIXED64},
        {"fl", Type.TYPE_FLOAT}, {"db", Type.TYPE_DOUBLE}, {"b", Type.TYPE_BOOL},
        {"s", Type.TYPE_STRING}, {"by", Type.TYPE_BYTES}};
    int number = 1;
    for (Object[] field : fields) {
      message.addField(FieldDescriptorProto.newBuilder().setName((String) field[0])
          .setNumber(number++).setType((Type) field[1]).setLabel(Label.LABEL_OPTIONAL));
    }
    message.addField(FieldDescriptorProto.newBuilder().setName("ri64").setNumber(number)
        .setType(Type.TYPE_INT64).setLabel(Label.LABEL_REPEATED));
    try {
      return FileDescriptor.buildFrom(FileDescriptorProto.newBuilder()
          .setName("form_scalars.proto").setSyntax("proto3").addMessageType(message).build(),
          new FileDescriptor[0]).findMessageTypeByName("Scalars");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Before
  public void before() {
    JsonFormat.clearFormBody();
  }

  @After
  public void after() {
    JsonFormat.clearFormBody();
  }

  private static DynamicMessage merge(String json, boolean formInput)
      throws JsonFormat.ParseException {
    DynamicMessage.Builder builder = DynamicMessage.newBuilder(SCALARS);
    mergeInto(json, builder, formInput, true);
    return builder.build();
  }

  private static void mergeInto(String json, Message.Builder builder, boolean formInput,
      boolean selfType) throws JsonFormat.ParseException {
    if (formInput) {
      JsonFormat.setFormBody(json);
    }
    try {
      JsonFormat.merge(json, builder, selfType);
    } finally {
      JsonFormat.clearFormBody();
    }
  }

  private static Object field(DynamicMessage message, String name) {
    return message.getField(SCALARS.findFieldByName(name));
  }

  private static String one(String name, String value) {
    return "{\"" + name + "\":\"" + value + "\"}";
  }

  @Test
  public void quotedScalarsParseInFormMode() throws Exception {
    DynamicMessage m = merge("{\"i32\":\"-1\",\"si32\":\"-2\",\"sf32\":\"-3\","
        + "\"i64\":\"-4\",\"si64\":\"-5\",\"sf64\":\"-6\",\"u32\":\"7\",\"f32\":\"8\","
        + "\"u64\":\"9\",\"f64\":\"10\",\"fl\":\"1.5\",\"db\":\"2.5\",\"b\":\"true\","
        + "\"ri64\":[\"1\",\"2\"]}", true);

    assertEquals(-1, field(m, "i32"));
    assertEquals(-2, field(m, "si32"));
    assertEquals(-3, field(m, "sf32"));
    assertEquals(-4L, field(m, "i64"));
    assertEquals(-5L, field(m, "si64"));
    assertEquals(-6L, field(m, "sf64"));
    assertEquals(7, field(m, "u32"));
    assertEquals(8, field(m, "f32"));
    assertEquals(9L, field(m, "u64"));
    assertEquals(10L, field(m, "f64"));
    assertEquals(1.5f, field(m, "fl"));
    assertEquals(2.5d, field(m, "db"));
    assertEquals(true, field(m, "b"));
    assertEquals(Arrays.asList(1L, 2L), field(m, "ri64"));
  }

  @Test
  public void quotedScalarsRejectedOutsideFormMode() {
    List<String[]> cases = Arrays.asList(
        new String[]{"i32", "1"}, new String[]{"si32", "1"}, new String[]{"sf32", "1"},
        new String[]{"i64", "1"}, new String[]{"si64", "1"}, new String[]{"sf64", "1"},
        new String[]{"u32", "1"}, new String[]{"f32", "1"}, new String[]{"u64", "1"},
        new String[]{"f64", "1"}, new String[]{"fl", "1.5"}, new String[]{"db", "1.5"},
        new String[]{"b", "true"});
    for (String[] c : cases) {
      assertThrows(c[0], JsonFormat.ParseException.class, () -> merge(one(c[0], c[1]), false));
    }
  }

  @Test
  public void unquotedJsonUnchangedInFormMode() throws Exception {
    DynamicMessage m = merge("{\"i64\":5,\"b\":false,\"db\":0.5}", true);

    assertEquals(5L, field(m, "i64"));
    assertEquals(false, field(m, "b"));
    assertEquals(0.5d, field(m, "db"));
  }

  @Test
  public void formIntegersAreDecimal() throws Exception {
    assertEquals(10L, field(merge(one("i64", "010"), true), "i64"));
    assertEquals(-7L, field(merge(one("i64", "-007"), true), "i64"));
    assertEquals(-10L, field(merge(one("i64", "-010"), true), "i64"));
    assertEquals(0L, field(merge(one("i64", "000"), true), "i64"));
    assertEquals(9L, field(merge(one("u64", "09"), true), "u64"));
    assertEquals(Long.MAX_VALUE, field(merge(one("i64", "0" + Long.MAX_VALUE), true), "i64"));
    assertEquals(8L, field(merge("{\"i64\":010}", false), "i64"));
  }

  @Test
  public void invalidIntegerValuesRejected() {
    for (String value : new String[]{"", "null", "abc", " 1", "1 ", "\\\"1", "0x10", "+5", "-",
        "1.0", "1e3", "１００", "١٢", "1１"}) {
      assertThrows(value, JsonFormat.ParseException.class, () -> merge(one("i64", value), true));
    }
  }

  @Test
  public void invalidBooleanValuesRejected() {
    for (String value : new String[]{"", "null", "TRUE", "1", "yes"}) {
      assertThrows(value, JsonFormat.ParseException.class, () -> merge(one("b", value), true));
    }
  }

  @Test
  public void floatingPointMatchesJsonTokens() throws Exception {
    assertEquals(1.5d, field(merge(one("db", "1.5"), true), "db"));
    assertEquals(1000.0d, field(merge(one("db", "1e3"), true), "db"));
    assertEquals(-0.5f, field(merge(one("fl", "-.5"), true), "fl"));

    String longDouble = "0." + repeat('0', 300) + "1";
    assertEquals(field(merge("{\"db\":" + longDouble + "}", false), "db"),
        field(merge(one("db", longDouble), true), "db"));

    for (String value : new String[]{"", " 1", "1 ", "1.5\\t", "１.5", "1,5"}) {
      assertThrows(value, JsonFormat.ParseException.class, () -> merge(one("db", value), true));
      assertThrows(value, JsonFormat.ParseException.class, () -> merge(one("fl", value), true));
    }
  }

  @Test
  public void integerLengthCheckedBeforeStrippingZeros() throws Exception {
    String atLimit = repeat('0', JsonFormat.MAX_INTEGER_TOKEN_LENGTH);
    assertEquals(0L, field(merge(one("i64", atLimit), true), "i64"));

    String overLimit = repeat('0', JsonFormat.MAX_INTEGER_TOKEN_LENGTH + 1);
    JsonFormat.ParseException e = assertThrows(JsonFormat.ParseException.class,
        () -> merge(one("i64", overLimit), true));
    assertTrue(e.getMessage(), e.getMessage().contains("Integer token is too long: length "
        + (JsonFormat.MAX_INTEGER_TOKEN_LENGTH + 1) + ", max "
        + JsonFormat.MAX_INTEGER_TOKEN_LENGTH));
  }

  @Test
  public void repeatedFormKeysParseEndToEnd() throws Exception {
    String converted = Util.getJsonString("ri64=1&ri64=02&i64=3&i64=4");
    JsonFormat.setFormBody(converted);
    DynamicMessage.Builder builder = DynamicMessage.newBuilder(SCALARS);
    JsonFormat.merge(converted, builder, true);

    assertEquals(Arrays.asList(1L, 2L), field(builder.build(), "ri64"));
    assertEquals(4L, field(builder.build(), "i64"));
  }

  @Test
  public void numericStringAndBytesUnchanged() throws Exception {
    DynamicMessage m = merge("{\"s\":\"1000001\"}", true);
    assertEquals("1000001", field(m, "s"));

    for (boolean selfType : new boolean[]{true, false}) {
      BytesMessage.Builder off = BytesMessage.newBuilder();
      mergeInto("{\"value\":\"1000001\"}", off, false, selfType);
      BytesMessage.Builder on = BytesMessage.newBuilder();
      mergeInto("{\"value\":\"1000001\"}", on, true, selfType);
      assertArrayEquals(off.getValue().toByteArray(), on.getValue().toByteArray());
    }
  }

  @Test
  public void quotedEnumUnchanged() throws Exception {
    for (boolean formInput : new boolean[]{true, false}) {
      FreezeBalanceV2Contract.Builder builder = FreezeBalanceV2Contract.newBuilder();
      mergeInto("{\"resource\":\"ENERGY\"}", builder, formInput, true);
      assertEquals(ResourceCode.ENERGY, builder.getResource());
    }
  }

  @Test
  public void onlyTheRegisteredBodyObjectIsLenient() throws Exception {
    String body = one("i64", "5");
    String sameContent = new String(body.toCharArray());
    JsonFormat.setFormBody(body);

    DynamicMessage.Builder first = DynamicMessage.newBuilder(SCALARS);
    JsonFormat.merge(body, first, true);
    assertEquals(5L, field(first.build(), "i64"));
    DynamicMessage.Builder again = DynamicMessage.newBuilder(SCALARS);
    JsonFormat.merge(body, again, true);
    assertEquals(5L, field(again.build(), "i64"));

    assertThrows(JsonFormat.ParseException.class,
        () -> JsonFormat.merge(sameContent, DynamicMessage.newBuilder(SCALARS), true));
    assertThrows(JsonFormat.ParseException.class,
        () -> JsonFormat.merge(new StringBuilder(body), DynamicMessage.newBuilder(SCALARS), true));
  }

  @Test
  public void repeatedValuesForSingularFieldKeepLast() throws Exception {
    NumberMessage.Builder builder = NumberMessage.newBuilder();
    mergeInto("{\"num\":[\"1\",\"2\"]}", builder, true, true);
    assertEquals(2L, builder.getNum());
  }

  @Test
  public void bodyDefaultsToAbsent() {
    assertFalse(JsonFormat.hasFormBody());
  }
}
