package org.tron.core.services.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import org.junit.Test;
import org.tron.common.utils.ByteArray;
import org.tron.core.Constant;
import org.tron.json.JSON;
import org.tron.protos.Protocol.Permission;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.contract.AccountContract.AccountPermissionUpdateContract;
import org.tron.protos.contract.AccountContract.AccountUpdateContract;
import org.tron.protos.contract.BalanceContract.TransferContract;
import org.tron.protos.contract.ProposalContract.ProposalCreateContract;

/*
 * A single-digit key resolves to the field with that number. These cases pin down that such a
 * numeric alias is parsed exactly like the field name: message fields take a JSON object (never a
 * string), scalar and bytes fields are unchanged, and keys that resolve to nothing are still
 * skipped.
 */
public class JsonFormatNumericAliasTest {

  private static final String OWNER_HEX = "41d3136787e667d1e055d2cd5db4b5f6c880563049";
  private static final String OWNER_BASE58 = "TVDGpn4hCSzJ5nkHPLetk8KQBtwaTppnkr";

  private static String permissionJson(String address) {
    return "{\"type\":\"Owner\",\"permission_name\":\"owner\",\"threshold\":2,"
        + "\"keys\":[{\"address\":\"" + address + "\",\"weight\":1},{\"weight\":1}]}";
  }

  private static String keysBody(String ownerKey, int count) {
    StringBuilder sb = new StringBuilder("{\"").append(ownerKey).append("\":{\"keys\":[");
    for (int i = 0; i < count; i++) {
      sb.append(i == 0 ? "{}" : ",{}");
    }
    return sb.append("]}}").toString();
  }

  private static void assertRejectedAsObject(String json, boolean visible) {
    AccountPermissionUpdateContract.Builder builder = AccountPermissionUpdateContract.newBuilder();
    JsonFormat.ParseException e = assertThrows(JsonFormat.ParseException.class,
        () -> JsonFormat.merge(json, builder, visible));
    assertTrue(e.getMessage(), e.getMessage().contains("Expected \"{\"."));
    assertFalse(builder.hasOwner());
    assertEquals(0, builder.getActivesCount());
  }

  @Test
  public void testNumericAliasParsesLikeFieldName() throws Exception {
    AccountPermissionUpdateContract.Builder named = AccountPermissionUpdateContract.newBuilder();
    AccountPermissionUpdateContract.Builder alias = AccountPermissionUpdateContract.newBuilder();

    JsonFormat.merge("{\"owner_address\":\"" + OWNER_HEX + "\",\"owner\":"
        + permissionJson(OWNER_HEX) + "}", named, false);
    JsonFormat.merge("{\"1\":\"" + OWNER_HEX + "\",\"2\":" + permissionJson(OWNER_HEX) + "}",
        alias, false);

    assertEquals(2, alias.getOwner().getKeysCount());
    assertEquals(named.build(), alias.build());
  }

  @Test
  public void testNumericAliasParsesLikeFieldNameWhenVisible() throws Exception {
    AccountPermissionUpdateContract.Builder named = AccountPermissionUpdateContract.newBuilder();
    AccountPermissionUpdateContract.Builder alias = AccountPermissionUpdateContract.newBuilder();

    JsonFormat.merge("{\"owner_address\":\"" + OWNER_BASE58 + "\",\"owner\":"
        + permissionJson(OWNER_BASE58) + "}", named, true);
    JsonFormat.merge("{\"1\":\"" + OWNER_BASE58 + "\",\"2\":" + permissionJson(OWNER_BASE58)
        + "}", alias, true);

    assertEquals(named.build(), alias.build());
    assertEquals(ByteString.copyFrom(ByteArray.fromHexString(OWNER_HEX)),
        alias.getOwner().getKeys(0).getAddress());
  }

  @Test
  public void testNumericAliasInsideNestedMessage() throws Exception {
    AccountPermissionUpdateContract.Builder named = AccountPermissionUpdateContract.newBuilder();
    AccountPermissionUpdateContract.Builder alias = AccountPermissionUpdateContract.newBuilder();

    JsonFormat.merge("{\"owner\":" + permissionJson(OWNER_HEX) + "}", named, false);
    // Permission: type = 1, permission_name = 3, threshold = 4, keys = 7
    JsonFormat.merge("{\"owner\":{\"1\":\"Owner\",\"3\":\"owner\",\"4\":2,\"7\":[{\"address\":\""
        + OWNER_HEX + "\",\"weight\":1},{\"weight\":1}]}}", alias, false);

    assertEquals(named.build(), alias.build());
  }

  @Test
  public void testNumericAliasRepeatedMessageField() throws Exception {
    Transaction.Builder named = Transaction.newBuilder();
    Transaction.Builder alias = Transaction.newBuilder();

    JsonFormat.merge("{\"ret\":[{\"fee\":1},{\"fee\":2}]}", named, false);
    JsonFormat.merge("{\"5\":[{\"fee\":1},{\"fee\":2}]}", alias, false);

    assertEquals(2, alias.getRetCount());
    assertEquals(named.build(), alias.build());
  }

  @Test
  public void testNumericAliasMessageFieldRejectsStringValue() {
    assertRejectedAsObject("{\"2\":\"3a003a00\"}", false);
  }

  @Test
  public void testNumericAliasMessageFieldRejectsStringValueWhenVisible() {
    assertRejectedAsObject("{\"2\":\"3a003a00\"}", true);
  }

  @Test
  public void testNumericAliasRepeatedMessageFieldRejectsStringElements() {
    assertRejectedAsObject("{\"4\":[\"3a00\",\"3a00\"]}", false);
  }

  @Test
  public void testNumericAliasNestedMessageFieldRejectsStringValue() {
    // Permission.keys = 7; "1001" would decode to a Key with weight 1.
    assertRejectedAsObject("{\"owner\":{\"threshold\":1,\"7\":\"1001\"}}", false);
    assertRejectedAsObject("{\"2\":{\"7\":[\"1001\"]}}", false);
  }

  @Test
  public void testNumericAliasMessageFieldRejectsEmptyString() {
    Transaction.Builder builder = Transaction.newBuilder();

    JsonFormat.ParseException e = assertThrows(JsonFormat.ParseException.class,
        () -> JsonFormat.merge("{\"1\":\"\"}", builder, false));

    assertTrue(e.getMessage().contains("Expected \"{\"."));
    assertFalse(builder.hasRawData());
  }

  @Test
  public void testNumericAliasScalarFieldsUnchanged() throws Exception {
    TransferContract.Builder named = TransferContract.newBuilder();
    TransferContract.Builder alias = TransferContract.newBuilder();
    JsonFormat.merge("{\"owner_address\":\"" + OWNER_HEX + "\",\"amount\":123}", named, false);
    JsonFormat.merge("{\"1\":\"" + OWNER_HEX + "\",\"3\":123}", alias, false);
    assertEquals(named.build(), alias.build());

    TransferContract.Builder visible = TransferContract.newBuilder();
    JsonFormat.merge("{\"1\":\"" + OWNER_BASE58 + "\"}", visible, true);
    assertEquals(alias.getOwnerAddress(), visible.getOwnerAddress());

    Permission.Builder permission = Permission.newBuilder();
    JsonFormat.merge("{\"1\":\"Active\",\"4\":3}", permission, false);
    assertEquals(Permission.PermissionType.Active, permission.getType());
    assertEquals(3, permission.getThreshold());
  }

  @Test
  public void testNumericAliasBytesFieldKeepsBinaryContent() throws Exception {
    // Any.value is bytes: a numeric alias must still store the raw bytes, not parse them.
    Any.Builder named = Any.newBuilder();
    Any.Builder alias = Any.newBuilder();
    JsonFormat.merge("{\"type_url\":\"type.googleapis.com/x\",\"value\":\"0a021001\"}",
        named, false);
    JsonFormat.merge("{\"1\":\"type.googleapis.com/x\",\"2\":\"0a021001\"}", alias, false);

    assertEquals(named.build(), alias.build());
    assertEquals(ByteString.copyFrom(ByteArray.fromHexString("0a021001")), alias.getValue());
  }

  @Test
  public void testNumericAliasEmptyObjectSetsDefaultInstance() throws Exception {
    // An empty JSON object sets the message field to its default instance, which is distinct
    // from the unset state that null and an empty array leave behind.
    AccountPermissionUpdateContract.Builder alias = AccountPermissionUpdateContract.newBuilder();
    AccountPermissionUpdateContract.Builder named = AccountPermissionUpdateContract.newBuilder();
    JsonFormat.merge("{\"2\":{}}", alias, false);
    JsonFormat.merge("{\"owner\":{}}", named, false);

    assertTrue(alias.hasOwner());
    assertEquals(named.build(), alias.build());
    assertEquals(Permission.getDefaultInstance(), alias.getOwner());
  }

  @Test
  public void testNumericAliasNullAndEmptyArrayLeaveFieldUnset() throws Exception {
    AccountPermissionUpdateContract.Builder builder = AccountPermissionUpdateContract.newBuilder();
    JsonFormat.merge("{\"2\":null}", builder, false);
    assertFalse(builder.hasOwner());

    JsonFormat.merge("{\"2\":[]}", builder, false);
    assertFalse(builder.hasOwner());

    JsonFormat.merge("{\"4\":[]}", builder, false);
    assertEquals(0, builder.getActivesCount());
  }

  @Test
  public void testKeysResolvingToNoFieldAreStillSkipped() throws Exception {
    // AccountPermissionUpdateContract has fields 1-4 only; "26" is not a single digit.
    AccountPermissionUpdateContract.Builder builder = AccountPermissionUpdateContract.newBuilder();

    JsonFormat.merge("{\"foo\":{\"a\":1},\"26\":1,\"9\":\"3a00\",\"0\":[1,2],"
        + "\"2\":{\"threshold\":1}}", builder, false);

    // Only the resolvable key contributes; message equality also covers unknown fields.
    assertEquals(AccountPermissionUpdateContract.newBuilder()
        .setOwner(Permission.newBuilder().setThreshold(1)).build(), builder.build());
  }

  @Test
  public void testUnknownFieldValueMatrixUnchanged() throws Exception {
    String[] skipped = {
        "{\"foo\":1}", "{\"foo\":\"x\"}", "{\"foo\":true}", "{\"foo\":null}",
        "{\"foo\":{\"a\":1}}", "{\"foo\":[1]}", "{\"foo\":-1}",
    };
    for (String json : skipped) {
      AccountPermissionUpdateContract.Builder builder =
          AccountPermissionUpdateContract.newBuilder();
      JsonFormat.merge(json, builder, false);
      assertEquals(json, AccountPermissionUpdateContract.getDefaultInstance(), builder.build());
    }

    String[] rejected = {
        "{\"foo\":{}}", "{\"foo\":[]}", "{\"foo\":9999999999999999999}", "{\"foo\":1.5}",
    };
    for (String json : rejected) {
      AccountPermissionUpdateContract.Builder builder =
          AccountPermissionUpdateContract.newBuilder();
      assertThrows(json, JsonFormat.ParseException.class,
          () -> JsonFormat.merge(json, builder, false));
    }
  }

  @Test
  public void testNumericAliasParsesAtJsonTokenLimit() throws Exception {
    // A body that the servlets' Jackson pre-parse accepts must produce the same number of
    // sub-messages through either spelling. {"k":{"keys":[ ... ]}} costs 8 tokens plus 2 per
    // empty element. The over-limit case belongs to the pre-parser and is covered by JsonTest.
    int within = (Constant.MAX_TOKEN_COUNT - 8) / 2;
    for (String key : new String[] {"owner", "2"}) {
      String body = keysBody(key, within);
      JSON.parseObject(body);
      AccountPermissionUpdateContract.Builder builder =
          AccountPermissionUpdateContract.newBuilder();
      JsonFormat.merge(body, builder, false);
      assertEquals(within, builder.getOwner().getKeysCount());
    }
  }

  @Test
  public void testLargeHexStringForMessageFieldIsRejected() {
    StringBuilder sb = new StringBuilder("{\"2\":\"");
    for (int i = 0; i < 100_000; i++) {
      sb.append("3a00");
    }
    sb.append("\"}");
    AccountPermissionUpdateContract.Builder builder = AccountPermissionUpdateContract.newBuilder();

    JsonFormat.ParseException e = assertThrows(JsonFormat.ParseException.class,
        () -> JsonFormat.merge(sb.toString(), builder, false));

    assertTrue(e.getMessage(), e.getMessage().contains("Expected \"{\"."));
    assertFalse(builder.hasOwner());
  }

  @Test
  public void testNamedKeyAndNumericAliasResolveToTheSameField() throws Exception {
    // A singular field keeps the value of whichever key comes last, the same way two keys with
    // the field name would.
    AccountPermissionUpdateContract.Builder aliasLast =
        AccountPermissionUpdateContract.newBuilder();
    JsonFormat.merge("{\"owner\":{\"threshold\":1},\"2\":{\"threshold\":2}}", aliasLast, false);
    assertEquals(2, aliasLast.getOwner().getThreshold());

    AccountPermissionUpdateContract.Builder nameLast = AccountPermissionUpdateContract.newBuilder();
    JsonFormat.merge("{\"2\":{\"threshold\":2},\"owner\":{\"threshold\":1}}", nameLast, false);
    assertEquals(1, nameLast.getOwner().getThreshold());

    AccountPermissionUpdateContract.Builder twoNames =
        AccountPermissionUpdateContract.newBuilder();
    JsonFormat.merge("{\"owner\":{\"threshold\":1},\"owner\":{\"threshold\":2}}",
        twoNames, false);
    assertEquals(twoNames.getOwner().getThreshold(), aliasLast.getOwner().getThreshold());

    // A repeated field appends from both spellings, again as two named keys would.
    AccountPermissionUpdateContract.Builder repeated =
        AccountPermissionUpdateContract.newBuilder();
    JsonFormat.merge("{\"actives\":[{}],\"4\":[{},{}]}", repeated, false);
    assertEquals(3, repeated.getActivesCount());

    Transaction.Builder transaction = Transaction.newBuilder();
    JsonFormat.merge("{\"raw_data\":{\"timestamp\":1},\"1\":{\"timestamp\":2}}",
        transaction, false);
    assertEquals(2, transaction.getRawData().getTimestamp());
  }

  @Test
  public void testFieldsWrittenBeforeAFailureAreKept() {
    // Merging is incremental and is not rolled back on failure, for a numeric alias exactly as
    // for the field name.
    AccountPermissionUpdateContract.Builder alias = AccountPermissionUpdateContract.newBuilder();
    assertThrows(JsonFormat.ParseException.class,
        () -> JsonFormat.merge("{\"2\":{\"threshold\":7},\"4\":[{},\"3a00\"]}", alias, false));
    assertEquals(7, alias.getOwner().getThreshold());
    assertEquals(1, alias.getActivesCount());

    AccountPermissionUpdateContract.Builder named = AccountPermissionUpdateContract.newBuilder();
    assertThrows(JsonFormat.ParseException.class,
        () -> JsonFormat.merge("{\"owner\":{\"threshold\":7},\"actives\":[{},\"3a00\"]}",
            named, false));
    assertEquals(named.getOwner().getThreshold(), alias.getOwner().getThreshold());
    assertEquals(named.getActivesCount(), alias.getActivesCount());
  }

  @Test
  public void testOnlySingleDigitKeysAreFieldAliases() throws Exception {
    // DIGITS matches one character, so only fields 1-9 can be addressed by number. A key with
    // two digits resolves to no field and is skipped like any other unknown key.
    Transaction.raw.Builder named = Transaction.raw.newBuilder();
    JsonFormat.merge("{\"contract\":[{\"type\":\"TransferContract\"}],\"fee_limit\":9}",
        named, false);
    assertEquals(1, named.getContractCount());
    assertEquals(9, named.getFeeLimit());

    Transaction.raw.Builder twoDigits = Transaction.raw.newBuilder();
    JsonFormat.merge("{\"11\":[{\"type\":\"TransferContract\"}],\"18\":9}", twoDigits, false);
    assertEquals(Transaction.raw.getDefaultInstance(), twoDigits.build());

    // A single-digit key in the same message still resolves (expiration = 8).
    Transaction.raw.Builder oneDigit = Transaction.raw.newBuilder();
    JsonFormat.merge("{\"8\":7}", oneDigit, false);
    assertEquals(7, oneDigit.getExpiration());
  }

  @Test
  public void testNumericAliasEnumAcceptsNumberAndName() throws Exception {
    // Permission.type = 1 is an enum: both spellings of the field accept both spellings of
    // the value.
    String[] bodies = {"{\"type\":\"Active\"}", "{\"type\":2}", "{\"1\":\"Active\"}",
        "{\"1\":2}"};
    for (String body : bodies) {
      Permission.Builder builder = Permission.newBuilder();
      JsonFormat.merge(body, builder, false);
      assertEquals(body, Permission.PermissionType.Active, builder.getType());
    }
  }

  @Test
  public void testNumericAliasForNameStringBytesFieldWhenVisible() throws Exception {
    // AccountUpdateContract: account_name = 1 (decoded as text under selfType), owner_address = 2.
    AccountUpdateContract.Builder named = AccountUpdateContract.newBuilder();
    AccountUpdateContract.Builder alias = AccountUpdateContract.newBuilder();
    JsonFormat.merge("{\"account_name\":\"alice\",\"owner_address\":\"" + OWNER_BASE58
        + "\"}", named, true);
    JsonFormat.merge("{\"1\":\"alice\",\"2\":\"" + OWNER_BASE58 + "\"}", alias, true);

    assertEquals(named.build(), alias.build());
    assertEquals(ByteString.copyFromUtf8("alice"), alias.getAccountName());
    assertEquals(ByteString.copyFrom(ByteArray.fromHexString(OWNER_HEX)), alias.getOwnerAddress());
  }

  @Test
  public void testNumericAliasForMapField() throws Exception {
    // ProposalCreateContract.parameters = 2 is a map, printed as repeated key/value messages.
    ProposalCreateContract.Builder named = ProposalCreateContract.newBuilder();
    ProposalCreateContract.Builder alias = ProposalCreateContract.newBuilder();
    JsonFormat.merge("{\"parameters\":[{\"key\":1,\"value\":2}]}", named, false);
    JsonFormat.merge("{\"2\":[{\"key\":1,\"value\":2}]}", alias, false);

    assertEquals(named.build(), alias.build());
    assertEquals(Long.valueOf(2), alias.build().getParametersMap().get(1L));

    // MapEntry itself has key = 1 and value = 2, so the entry accepts aliases too.
    ProposalCreateContract.Builder entryAlias = ProposalCreateContract.newBuilder();
    JsonFormat.merge("{\"2\":[{\"1\":5,\"2\":6}]}", entryAlias, false);
    assertEquals(Long.valueOf(6), entryAlias.build().getParametersMap().get(5L));

    ProposalCreateContract.Builder hex = ProposalCreateContract.newBuilder();
    JsonFormat.ParseException e = assertThrows(JsonFormat.ParseException.class,
        () -> JsonFormat.merge("{\"2\":\"08011002\"}", hex, false));
    assertTrue(e.getMessage().contains("Expected \"{\"."));
    assertTrue(hex.build().getParametersMap().isEmpty());
  }
}
