package tech.resource;

import java.lang.ref.*;
import java.util.function.Function;
import java.lang.Runnable;
import clojure.lang.IDeref;


public class GCSoftReference
  extends SoftReference<Object>
  implements Runnable, IDeref
{
  Function<Object,Object> disposer;
  public GCSoftReference( Object item, ReferenceQueue<Object> q,
		      Function<Object,Object> _disposer)
  {
    super(item, q);
    disposer = _disposer;
  }
  public void run()
  {
    synchronized(this) {
      if(disposer != null) {
	disposer.apply(this);
	disposer = null;
      }
    }
  }
  public Object deref() { return get(); }
}
