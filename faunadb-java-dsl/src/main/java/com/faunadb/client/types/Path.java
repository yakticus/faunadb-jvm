package com.faunadb.client.types;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.List;

import static com.faunadb.client.types.Codec.ARRAY;
import static com.faunadb.client.types.Codec.OBJECT;
import static java.lang.String.format;

final class Path {

  private static abstract class Segment<T> {
    final T segment;

    private Segment(T segment) {
      this.segment = segment;
    }

    abstract Result<Value> get(Value root);

    @Override
    public boolean equals(Object other) {
      return other != null &&
        other instanceof Segment &&
        this.segment.equals(((Segment) other).segment);
    }

    @Override
    public int hashCode() {
      return segment.hashCode();
    }

    @Override
    public String toString() {
      return segment.toString();
    }
  }

  private static final class ObjectKey extends Segment<String> {
    private ObjectKey(String segment) {
      super(segment);
    }

    @Override
    public Result<Value> get(Value root) {
      return root.to(OBJECT).flatMap(new Function<ImmutableMap<String, Value>, Result<Value>>() {
        @Override
        public Result<Value> apply(ImmutableMap<String, Value> obj) {
          Value value = obj.get(segment);
          if (value != null)
            return Result.success(value);

          return Result.fail(format("Object key \"%s\" not found", segment));
        }
      });
    }

  }

  private static final class ArrayIndex extends Segment<Integer> {
    private ArrayIndex(Integer segment) {
      super(segment);
    }

    @Override
    public Result<Value> get(Value root) {
      return root.to(ARRAY).flatMap(new Function<ImmutableList<Value>, Result<Value>>() {
        @Override
        public Result<Value> apply(ImmutableList<Value> array) {
          try {
            return Result.success(array.get(segment));
          } catch (IndexOutOfBoundsException ign) {
            return Result.fail(format("Array index \"%s\" not found", segment));
          }
        }
      });
    }

  }

  static Path empty() {
    return new Path(ImmutableList.<Segment>of());
  }

  static Path from(String... keys) {
    ImmutableList.Builder<Segment> segments = ImmutableList.builder();

    for (String key : keys)
      segments.add(new ObjectKey(key));

    return new Path(segments.build());
  }

  static Path from(int... indexes) {
    ImmutableList.Builder<Segment> segments = ImmutableList.builder();

    for (Integer index : indexes)
      segments.add(new ArrayIndex(index));

    return new Path(segments.build());
  }

  private final List<Segment> segments;

  private Path(List<Segment> segments) {
    this.segments = segments;
  }

  Path subPath(Path other) {
    ImmutableList.Builder<Segment> newSegments = ImmutableList.builder();
    newSegments.addAll(segments);
    newSegments.addAll(other.segments);

    return new Path(newSegments.build());
  }

  Result<Value> get(Value root) {
    Result<Value> result = Result.success(root);

    for (Segment<?> segment : segments) {
      result = segment.get(result.get());
      if (result.isFailure())
        return Result.fail(
          format("Can not find path \"%s\". %s", this, result));
    }

    return result;
  }

  @Override
  public boolean equals(Object other) {
    return other != null &&
      other instanceof Path &&
      this.segments.equals(((Path) other).segments);
  }

  @Override
  public int hashCode() {
    return segments.hashCode();
  }

  @Override
  public String toString() {
    return Joiner.on("/").join(segments);
  }

}
