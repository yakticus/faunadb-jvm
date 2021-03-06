package com.faunadb.client.query;

/**
 * A query language expression. Constructors for this class are at the {@link Language} class.
 */
public abstract class Expr {

  // To be used by Jackson
  protected abstract Object toJson();

  @Override
  public String toString() {
    return String.format("%s(%s)", getClass().getSimpleName(), toJson());
  }
}
