package tech.resource;

import java.lang.ref.*;
import java.util.concurrent.Callable;



public class GCReference extends WeakReference<Object>
{
  Callable disposer;
  public GCReference( Object item, ReferenceQueue<Object> q, Callable _disposer)
  {
    super(item, q);
    disposer = _disposer;
  }
  public void dispose() throws Exception
  {
    synchronized(this) {
      if(disposer != null) {
	disposer.call();
	disposer = null;
      }
    }
  }
}
