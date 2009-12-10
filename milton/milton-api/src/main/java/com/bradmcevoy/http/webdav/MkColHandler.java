package com.bradmcevoy.http.webdav;

import com.bradmcevoy.common.Path;
import com.bradmcevoy.http.*;
import com.bradmcevoy.http.Request.Method;
import com.bradmcevoy.http.Response.Status;
import com.bradmcevoy.http.exceptions.BadRequestException;
import com.bradmcevoy.http.exceptions.ConflictException;
import com.bradmcevoy.http.exceptions.NotAuthorizedException;
import com.bradmcevoy.http.http11.Http11ResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MkColHandler implements Handler {

    private static final Logger log = LoggerFactory.getLogger( MkColHandler.class );
    private final Http11ResponseHandler responseHandler;
    private final HandlerHelper handlerHelper;

    public MkColHandler( Http11ResponseHandler responseHandler, HandlerHelper handlerHelper ) {
        this.responseHandler = responseHandler;
        this.handlerHelper = handlerHelper;
    }

    public String[] getMethods() {
        return new String[]{Method.MKCOL.code};
    }

    @Override
    public boolean isCompatible( Resource handler ) {
        return ( handler instanceof MakeCollectionableResource );
    }

    public void process( HttpManager manager, Request request, Response response ) throws ConflictException, NotAuthorizedException, BadRequestException {
        if( !handlerHelper.checkExpects( responseHandler, request, response ) ) {
            return;
        }
        String host = request.getHostHeader();
        String finalurl = HttpManager.decodeUrl( request.getAbsolutePath() );
        String name;
        log.debug( "process request: host: " + host + " url: " + finalurl );

        Path finalpath = Path.path( finalurl ); //this is the parent collection it goes in
        name = finalpath.getName();
        Path parent = finalpath.getParent();
        String parenturl = parent.toString();

        Resource parentcol = manager.getResourceFactory().getResource( host, parenturl );
        if( parentcol != null ) {
            log.debug( "process: resource: " + parentcol.getClass().getName() );

            if( handlerHelper.isLockedOut( request, parentcol ) ) {
                response.setStatus( Status.SC_LOCKED );
                return;
            }
            Resource dest = manager.getResourceFactory().getResource( host, finalpath.toString() );

            if( dest != null && handlerHelper.isLockedOut( request, dest ) ) {
                response.setStatus( Status.SC_LOCKED ); //notowner_modify wants this code here
                return;
            } else if( handlerHelper.missingLock( request, parentcol ) ) {
                response.setStatus( Status.SC_PRECONDITION_FAILED ); //notowner_modify wants this code here
                return;
            }

            if( parentcol instanceof CollectionResource ) {
                CollectionResource col = (CollectionResource) parentcol;
                processMakeCol( manager, request, response, col, name );
            } else {
                responseHandler.respondConflict( parentcol, response, request, "not a collection" );
            }
            
        } else {
            manager.getResponseHandler().respondConflict( parentcol, response, request, name );
        }
    }

    protected void processMakeCol( HttpManager milton, Request request, Response response, CollectionResource resource, String newName ) throws ConflictException, NotAuthorizedException {
        MakeCollectionableResource existingCol = (MakeCollectionableResource) resource;
        try {
            //For litmus test and RFC support
            if( request.getInputStream().read() > -1 ) //This should be empty
            {
                response.setStatus( Response.Status.SC_UNSUPPORTED_MEDIA_TYPE );
                return;
            }
        } catch( Exception ex ) {
            ex.printStackTrace();
        }
        Resource existingChild = existingCol.child( newName );
        if( existingChild != null ) {
            log.debug( "item already exists: " + existingChild.getName() );
            throw new ConflictException( existingChild );
        }
        CollectionResource made = existingCol.createCollection( newName );
        if( made == null ) {
            response.setStatus( Response.Status.SC_METHOD_NOT_ALLOWED );
        } else {
            response.setStatus( Response.Status.SC_CREATED );
        }
    }
}