package com.faunadb.client.response;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.faunadb.client.types.Ref;
import com.google.common.collect.ImmutableList;

/**
 * An abstract node in a FaunaDB response tree. An instance of this class does not have any accessible data. It must
 * first be coerced into a concrete response type, or a concrete value type.
 *
 * <p>Coercion functions will return null if this node cannot be transformed into the requested type.
 *
 * <p><b>Example</b>: Consider the {@code ResponseNode node} modeling the root of the tree:</p>
 * <pre>
 * {
 *   "ref": { "@ref": "some/ref" },
 *   "data": { "someKey": "string1", "someKey2": 123 }
 * }</pre>
 *
 * <p>The result tree can be accessed using:</p>
 *
 * <pre>
 *   node.get("ref").asRef(); // {@link Ref}("some/ref")
 *   node.get("data").get("someKey").asString() // "string1"
 * </pre>
 */
@JsonDeserialize(using=Codec.ResponseNodeDeserializer.class)
public final class ResponseNode {
  public static ResponseNode create(JsonNode underlying, ObjectMapper json) {
    return new ResponseNode(underlying, json);
  }

  private final JsonNode underlying;
  private final ObjectMapper json;

  @JsonCreator
  ResponseNode(JsonNode underlying, ObjectMapper json) {
    this.underlying = underlying;
    this.json = json;
  }

  /**
   * Coerces this node into a {@link String}.
   * @return the string value of this node, or null.
   */
  public String asString() {
    return underlying.asText();
  }

  /**
   * Coerces this node into a {@link Boolean}.
   * @return the boolean value of this node, or null.
   */
  public Boolean asBoolean() {
    if (underlying.isBoolean()) {
      return underlying.asBoolean();
    } else {
      return null;
    }
  }

  /**
   * Coerces this node into a {@link Long}.
   * @return the long value of this node, or null.
   */
  public Long asLong() {
    if (underlying.isNumber()) {
      return underlying.asLong();
    } else {
      return null;
    }
  }

  /**
   * Coerces this node into a {@link Double}.
   * @return the double value of this node, or null.
   */
  public Double asDouble() {
    if (underlying.isDouble()) {
      return underlying.asDouble();
    } else {
      return null;
    }
  }

  /**
   * Coerces this node into an ordered list of nodes.
   * @return an ordered list of response nodes, or null.
   */
  public ImmutableList<ResponseNode> asArray() {
    return json.convertValue(underlying, TypeFactory.defaultInstance().constructCollectionType(ImmutableList.class, ResponseNode.class));
  }

  /**
   * Coerces this node into a dictionary of nodes.
   * @return a dictionary of nodes, or null.
   */
  public ResponseMap asObject() {
    return json.convertValue(underlying, ResponseMap.class);
  }

  /**
   * Coerces this node into a {@link Ref}.
   * @return a Ref, or null.
   */
  public Ref asRef() {
    return json.convertValue(underlying, Ref.class);
  }

  /**
   * Coerces this node into a {@link Page}.
   * @return a Page, or null.
   */
  public Page asPage() {
    return json.convertValue(underlying, Page.class);
  }

  /**
   * Coerces this node into an {@link Instance}.
   * @return an Instance, or null.
   */
  public Instance asInstance() {
    return json.convertValue(underlying, Instance.class);
  }

  /**
   * Coerces this node into a {@link Key}.
   * @return a Key, or null.
   */
  public Key asKey() {
    return json.convertValue(underlying, Key.class);
  }

  /**
   * Coerces this node into a {@link Database}.
   * @return a Database, or null.
   */
  public Database asDatabase() {
    return json.convertValue(underlying, Database.class);
  }

  /**
   * Coerces this node into a {@link Class}.
   * @return a Class, or null.
   */
  public Class asClass() {
    return json.convertValue(underlying, Class.class);
  }

  /**
   * Coerces this node into an {@link Index}.
   * @return an Index, or null.
   */
  public Index asIndex() {
    return json.convertValue(underlying, Index.class);
  }

  /**
   * Coerces this node into an {@link Event}.
   * @return an Event, or null.
   */
  public Event asEvent() {
    return json.convertValue(underlying, Event.class);
  }

  /**
   * Coerces this node into a {@link Set}.
   * @return a Set, or null.
   */
  public Set asSet() {
    return json.convertValue(underlying, Set.class);
  }

  /**
   * Accesses the value of the specified field if this is an object node.
   * @return the value of the field, or null.
   */
  public ResponseNode get(String key) {
    return asObject().get(key);
  }

  /**
   * Accesses the value of the specified element if this is an array node.
   * @return the value of the element, or null.
   */
  public ResponseNode get(int index) {
    return asArray().get(index);
  }

  @Override
  public boolean equals(Object obj) {
    return (obj instanceof ResponseNode) && underlying.equals(((ResponseNode) obj).underlying);
  }

  @Override
  public int hashCode() {
    return underlying.hashCode();
  }

  @Override
  public String toString() {
    return underlying.toString();
  }
}