package tech.resource;

import java.lang.ref.*;
import java.util.function.Function;



public class GCReference extends WeakReference<Object>
{
  Function<Object,Object> disposer;
  public GCReference( Object item, ReferenceQueue<Object> q, Function<Object,Object> _disposer)
  {
    super(item, q);
    disposer = _disposer;
  }
  public void dispose() throws Exception
  {
    synchronized(this) {
      if(disposer != null) {
	disposer.apply(this);
	disposer = null;
      }
    }
  }
}
